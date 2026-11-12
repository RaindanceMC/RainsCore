package de.raindancer.core.moderation.audit;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A record of what was done on this server, and who did it.
 *
 * <h2>What it is for</h2>
 * Two questions, asked when something has already gone wrong: <b>what has this moderator been
 * doing</b>, and <b>what has been done to this player</b>. Both are unanswerable from an ordinary
 * logfile, which is a stream of sentences with no structure to search — so this keeps the actor, the
 * subject and the feature as fields and generates the sentence when somebody wants to read one.
 *
 * <p>It is deliberately not the ordinary log. The logfile is for the server operator debugging the
 * server; this is for the person asking who took the diamonds. They have different retention, they
 * are read in different ways, and mixing them means neither is good at its job.
 *
 * <h2>Why recording does not touch the disk</h2>
 * Because it is called from wherever the action happened — a click handler, a command, a login — and
 * those are on threads that are running the world. {@link #record} appends to a queue and returns;
 * {@link #flush()} writes whatever has gathered, in one transaction, off the server's threads and on
 * a timer.
 *
 * <p>That trades a few seconds of durability for never stalling a region, which is the right way
 * round: an audit line lost to a crash is an inconvenience, and a region stalling on an fsync every
 * time somebody opens a menu is a server nobody plays on. A clean shutdown flushes, so the only way
 * to lose one is a kill.
 *
 * <h2>Why it is written even when nobody will ever read it</h2>
 * Because the value of an audit log is entirely in already having it. Nobody turns one on before the
 * incident.
 */
public final class Audit {

    private static final LogChannel log = Log.of("audit");

    /**
     * How many entries may wait to be written.
     *
     * <p>Large enough that no realistic burst reaches it — a moderator clearing an inventory is a few
     * dozen lines — and bounded so that a database that has stopped accepting writes cannot turn a
     * growing queue into a server that runs out of memory.
     */
    private static final int QUEUE_LIMIT = 20_000;

    private final Database database;
    private final LongSupplier clock;

    private final ConcurrentLinkedQueue<AuditEntry> pending = new ConcurrentLinkedQueue<>();
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final java.util.List<java.util.function.Consumer<AuditEntry>> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Stops listening when closed. */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Hears every entry as it is recorded — even one the full queue then drops — on the recording thread, so it must
     * be quick. One that throws is skipped.
     */
    public Subscription listen(java.util.function.Consumer<AuditEntry> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public Audit(Database database, LongSupplier clock) {
        this.database = database;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------- recording

    /**
     * Writes down that something happened. Never blocks and never throws.
     *
     * @return whether it was taken; false only when the queue is full, which means writes are failing
     */
    public boolean record(AuditEntry entry) {
        if (entry == null) {
            return false;
        }
        for (java.util.function.Consumer<AuditEntry> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (RuntimeException failed) {
                log.warn("An audit listener failed: {}", failed.toString());
            }
        }
        if (queued.get() >= QUEUE_LIMIT) {
            // Counted rather than logged per entry: whatever is wrong is already being logged by the
            // database, and a full queue would otherwise produce a second flood on top of the first.
            dropped.incrementAndGet();
            return false;
        }
        pending.add(entry);
        queued.incrementAndGet();
        return true;
    }

    /** The common case: an entry stamped with now. */
    public boolean record(AuditEntry.Builder entry) {
        return entry != null && record(entry.at(Instant.ofEpochMilli(clock.getAsLong())));
    }

    /** How many entries are waiting to be written. */
    public int waiting() {
        return (int) queued.get();
    }

    /** How many were thrown away because the queue was full — zero on a healthy server. */
    public long droppedEntries() {
        return dropped.get();
    }

    /** How many have been written since this started. */
    public long writtenEntries() {
        return written.get();
    }

    // ----------------------------------------------------------------------------- writing

    /**
     * Writes everything that has gathered, in one transaction.
     *
     * <p>Must be called off the server's threads. One transaction for the whole batch rather than one
     * each: with {@code synchronous=FULL} a transaction costs an fsync, so a hundred entries written
     * separately is a hundred of them and written together is one.
     *
     * @return how many were written
     */
    public int flush() {
        synchronized (flushing) {
            return writeChanges();
        }
    }

    /**
     * One flush at a time. The saving timer can still be mid-flush when shutdown flushes by hand, and
     * two at once either land out of order or answer "written" while the other still holds the rows.
     */
    private final Object flushing = new Object();

    private int writeChanges() {
        if (pending.isEmpty() || !database.isUsable()) {
            return 0;
        }
        // Drained into a list first, so the batch is a fixed size. Writing straight from the queue
        // would let entries arriving during the write extend the transaction indefinitely on a busy
        // server — and hold the write lock for as long as anybody kept adding.
        List<AuditEntry> batch = new ArrayList<>();
        AuditEntry next;
        while ((next = pending.poll()) != null) {
            batch.add(next);
            queued.decrementAndGet();
        }
        if (batch.isEmpty()) {
            return 0;
        }

        boolean ok = database.write(connection -> {
            try (PreparedStatement entry = connection.prepareStatement("""
                    INSERT INTO entry (at, feature, action, actor, actor_name, subject,
                                       subject_name, detail, world)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement field = connection.prepareStatement(
                         "INSERT INTO entry_field (entry, name, value) VALUES (?, ?, ?)")) {
                for (AuditEntry one : batch) {
                    entry.setLong(1, one.at().toEpochMilli());
                    entry.setString(2, one.feature());
                    entry.setString(3, one.action());
                    entry.setString(4, asText(one.actor()));
                    entry.setString(5, one.actorName());
                    entry.setString(6, asText(one.subject()));
                    entry.setString(7, one.subjectName());
                    entry.setString(8, one.detail());
                    entry.setString(9, one.world());
                    entry.executeUpdate();
                    if (one.fields().isEmpty()) {
                        continue;
                    }
                    long id = generatedId(entry);
                    if (id <= 0) {
                        // The entry itself is in; only its extra fields are lost. Worth a line,
                        // because a driver that stops returning generated keys is worth knowing
                        // about, and worth carrying on for the same reason.
                        log.warn("An audit entry was written but its id came back as {}, so its "
                                + "{} extra field(s) could not be attached.", id,
                                one.fields().size());
                        continue;
                    }
                    for (Map.Entry<String, String> extra : one.fields().entrySet()) {
                        field.setLong(1, id);
                        field.setString(2, extra.getKey());
                        field.setString(3, extra.getValue());
                        field.executeUpdate();
                    }
                }
            }
        });

        if (!ok) {
            // Not put back on the queue. A batch that failed will fail again — a broken database, a
            // full disk — and re-queueing it would retry for ever, growing the queue until the
            // server runs out of memory over an audit log. Said plainly instead.
            dropped.addAndGet(batch.size());
            log.error("{} audit entries could not be written and have been lost. The reason is "
                    + "above this line.", batch.size());
            return 0;
        }
        written.addAndGet(batch.size());
        return batch.size();
    }

    private static long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            return keys.next() ? keys.getLong(1) : 0L;
        }
    }

    // ----------------------------------------------------------------------------- reading

    /**
     * Everything matching, newest first.
     *
     * <p>Reads the database only: entries still waiting to be written are not included, because a
     * search that sometimes shows an entry and sometimes does not — depending on where the timer
     * happens to be — is worse than one that is a few seconds behind and consistent.
     *
     * <p>Must be called off the server's threads.
     */
    public List<AuditEntry> search(AuditSearch what) {
        AuditSearch looking = what == null ? AuditSearch.everything() : what;
        StringBuilder sql = new StringBuilder("""
                SELECT id, at, feature, action, actor, actor_name, subject, subject_name,
                       detail, world
                FROM entry WHERE 1 = 1""");
        List<Object> values = new ArrayList<>();
        // Built by hand but never by concatenating a value: every one of these is a placeholder, so
        // a player whose name contains a quote is a name rather than an injection.
        if (looking.actor() != null) {
            sql.append(" AND actor = ?");
            values.add(looking.actor().toString());
        }
        if (looking.subject() != null) {
            sql.append(" AND subject = ?");
            values.add(looking.subject().toString());
        }
        if (looking.feature() != null) {
            sql.append(" AND feature = ?");
            values.add(looking.feature());
        }
        if (looking.action() != null) {
            sql.append(" AND action = ?");
            values.add(looking.action());
        }
        if (looking.since() != null) {
            sql.append(" AND at >= ?");
            values.add(looking.since().toEpochMilli());
        }
        if (looking.until() != null) {
            sql.append(" AND at <= ?");
            values.add(looking.until().toEpochMilli());
        }
        sql.append(" ORDER BY at DESC, id DESC LIMIT ?");
        values.add(looking.limit());

        return database.read(connection -> {
            List<AuditEntry> found = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                for (int at = 0; at < values.size(); at++) {
                    statement.setObject(at + 1, values.get(at));
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        found.add(readOne(rows));
                    }
                }
            }
            attachFields(connection, found);
            return found;
        }).orElseGet(List::of);
    }

    /** One entry by the id the journal gave it. */
    public Optional<AuditEntry> byId(long id) {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, at, feature, action, actor, actor_name, subject, subject_name,
                           detail, world
                    FROM entry WHERE id = ?""")) {
                statement.setLong(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return null;
                    }
                    AuditEntry one = readOne(rows);
                    List<AuditEntry> asList = new ArrayList<>(List.of(one));
                    attachFields(connection, asList);
                    return asList.get(0);
                }
            }
        });
    }

    /** How many entries there are in total. */
    public long count() {
        return database.read(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT count(*) FROM entry");
                 ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }).orElse(0L);
    }

    /**
     * Reads the extra fields for a page of entries in one query rather than one each.
     *
     * <p>A hundred entries with their fields fetched separately is a hundred and one queries, which
     * on a screen somebody pages through is the difference between instant and noticeable. The ids
     * are numbers this class read out of the database a moment ago, so building the list of
     * placeholders from their count is safe.
     */
    private static void attachFields(Connection connection, List<AuditEntry> entries)
            throws SQLException {
        if (entries.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(entries.size(), "?"));
        Map<Long, Map<String, String>> byEntry = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT entry, name, value FROM entry_field WHERE entry IN (" + placeholders + ")")) {
            for (int at = 0; at < entries.size(); at++) {
                statement.setLong(at + 1, entries.get(at).id());
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    byEntry.computeIfAbsent(rows.getLong("entry"), key -> new LinkedHashMap<>())
                            .put(rows.getString("name"), rows.getString("value"));
                }
            }
        }
        for (int at = 0; at < entries.size(); at++) {
            AuditEntry one = entries.get(at);
            Map<String, String> fields = byEntry.get(one.id());
            if (fields != null) {
                entries.set(at, new AuditEntry(one.id(), one.at(), one.feature(), one.action(),
                        one.actor(), one.actorName(), one.subject(), one.subjectName(),
                        one.detail(), one.world(), fields));
            }
        }
    }

    private static AuditEntry readOne(ResultSet rows) throws SQLException {
        return new AuditEntry(
                rows.getLong("id"),
                Instant.ofEpochMilli(rows.getLong("at")),
                rows.getString("feature"),
                rows.getString("action"),
                asUuid(rows.getString("actor")),
                rows.getString("actor_name"),
                asUuid(rows.getString("subject")),
                rows.getString("subject_name"),
                rows.getString("detail"),
                rows.getString("world"),
                Map.of());
    }

    // --------------------------------------------------------------------------- forgetting

    /**
     * Deletes entries older than the given age.
     *
     * <p>Not optional and not merely housekeeping: these rows name real people and say what they did,
     * which under the GDPR is personal data that may be kept for as long as there is a reason and no
     * longer. A journal that grows for ever is a compliance problem as much as a disk problem, so the
     * retention period is a setting and this runs on a timer.
     *
     * <p>Must be called off the server's threads.
     *
     * @return how many were deleted
     */
    public int forgetOlderThan(Duration age) {
        if (age == null || age.isZero() || age.isNegative() || !database.isUsable()) {
            return 0;
        }
        long before = clock.getAsLong() - age.toMillis();
        int[] deleted = {0};
        database.write(connection -> {
            // The fields go with the entry by ON DELETE CASCADE, which is why foreign keys are
            // switched on — without that these rows would stay behind with nothing pointing at them.
            try (PreparedStatement statement =
                         connection.prepareStatement("DELETE FROM entry WHERE at < ?")) {
                statement.setLong(1, before);
                deleted[0] = statement.executeUpdate();
            }
        });
        if (deleted[0] > 0) {
            log.info("Forgot {} audit entries older than {}.", deleted[0], age);
        }
        return deleted[0];
    }

    private static String asText(UUID id) {
        return id == null ? null : id.toString();
    }

    private static UUID asUuid(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAUuid) {
            // A row written by something else, or by hand. Worth showing the rest of the entry for.
            return null;
        }
    }
}
