package de.raindancer.core.moderation.punishment;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.util.Marks;
import de.raindancer.core.platform.network.NetworkBus;

import java.nio.file.Path;
import java.time.Instant;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;
import java.util.Map;

/**
 * Bans, mutes, freezes, and the record of who did what.
 *
 * <h2>Why this is here rather than in a moderation plugin</h2>
 * Every plugin that can refuse a player something eventually grows its own way of remembering that
 * it refused them. Separately, that means several files that disagree about whether somebody is
 * muted, several answers to "when does this end", and nowhere to look when a player asks why they
 * cannot build. Any plugin can ask this one, and the claims module can freeze somebody's hands
 * without having an opinion about whether they should be on the server at all.
 *
 * <h2>Nothing is ever deleted</h2>
 * Lifting a ban adds the lifting to it; the ban stays. That is what makes a second offence
 * answerable, and it is the difference between this and a set of flags.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. Each player's record is an immutable list, swapped whole under the map's
 * per-key lock — punishments are read constantly (every join, every chat message) and written rarely.
 * A swap rather than an edit in place is what lets a {@link #refresh} replace a player's records
 * without a moment in which a check finds nothing and lets a banned player in.
 *
 * <h2>On a network (2.0.0)</h2>
 * Every server keeps this cache of the one central table. A server that wrote a change tells the
 * others over the {@link NetworkBus} ({@value #TOPIC}, keyed by the player), and they read that
 * player again. A join reads the player from the database itself, so a ban is enforced even if the
 * event never arrived.
 */
public final class Punishments {

    private static final LogChannel log = Log.of("moderation");

    private final Database database;
    private final LongSupplier clock;
    /** The topic other servers are told on, keyed by the punished player's id. */
    public static final String TOPIC = "punishments";

    private final Map<UUID, List<Punishment>> byPlayer = new ConcurrentHashMap<>();

    private volatile NetworkBus bus;

    /**
     * Which punishments have changed and need writing.
     *
     * <p>Ids rather than a single dirty flag, because this table only ever grows: a server that has
     * been running for a year has every ban it ever handed out in here, and rewriting all of them
     * every two minutes to save the one that just changed is work that grows without bound. A
     * handful of ids is what actually changed.
     */
    private final Set<String> changed = ConcurrentHashMap.newKeySet();

    /** Where a write is handed the moment something changes; null leaves it to the saving timer. */
    private volatile Consumer<Runnable> writeSoon;

    /** @param clock milliseconds; injected so expiry can be tested without waiting for it */
    public Punishments(Database database, LongSupplier clock) {
        this.database = database;
        this.clock = clock;
    }

    /**
     * Has every change written straight away, through {@code writeSoon} — Core passes its async
     * scheduler. A ban is rare and must not be lost: left to the saving timer, a crash inside its two
     * minutes forgot it, and the banned player walked back in.
     */
    public void writeSoon(Consumer<Runnable> writeSoon) {
        this.writeSoon = writeSoon;
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.getAsLong());
    }

    // ---------------------------------------------------------------------------- punishing

    /**
     * Records a punishment, replacing any of the same kind that is still in force.
     *
     * @param length how long for; null means until somebody lifts it
     * @return the punishment, so the caller can tell the player about it
     */
    public Punishment punish(UUID target, PunishmentKind kind, UUID moderator, String reason,
                             Duration length) {
        Instant now = now();
        Punishment given = Punishment.given(target, kind, moderator, reason, now, length);
        byPlayer.compute(target, (player, old) -> {
            List<Punishment> next = new ArrayList<>(old == null ? List.of() : old);
            // The old one is lifted rather than dropped, so the history still shows both. A moderator
            // extending a mute has done two things and the record should say so.
            for (int at = 0; at < next.size(); at++) {
                Punishment each = next.get(at);
                if (each.kind() == kind && each.isActiveAt(now)) {
                    Punishment lifted = each.lifted(moderator, "replaced", now);
                    next.set(at, lifted);
                    changed.add(lifted.id());
                }
            }
            next.add(given);
            changed.add(given.id());
            return List.copyOf(next);
        });
        writeSoon();
        log.info("{} was {} by {} for '{}' ({})", target, kind.past(), moderator, given.reason(),
                given.length());
        return given;
    }

    /**
     * The player's mute and ban as they stand here, as plain strings — for another server of the network to
     * {@link #adopt}. An end of "" is "until lifted".
     */
    public java.util.Map<String, String> export(UUID player) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (PunishmentKind kind : java.util.List.of(PunishmentKind.MUTE, PunishmentKind.BAN)) {
            active(player, kind).ifPresent(given -> {
                String key = kind.name().toLowerCase(java.util.Locale.ROOT);
                out.put(key, "true");
                out.put(key + ".reason", given.reason());
                out.put(key + ".moderator", given.moderator() == null ? "" : given.moderator().toString());
                out.put(key + ".ends", given.endsAt() == null ? "" : String.valueOf(given.endsAt().toEpochMilli()));
            });
        }
        return out;
    }

    /** Marks a mute that came from the linked server, so only those are ever lifted or changed by it. */
    static final String LINKED = " (linked server)";

    /**
     * Brings over a mute another server {@link #export exported}, for the time it has left there, and lifts one
     * that came from there once it is gone there. A mute given on this server is never lifted or shortened by the
     * other's state — only a longer one from there replaces it. A ban is not copied — see {@link #banned}.
     */
    public void adopt(UUID player, java.util.Map<String, String> exported) {
        if (player == null || exported == null) {
            return;
        }
        Optional<Punishment> here = active(player, PunishmentKind.MUTE);
        boolean mirrored = here.isPresent() && here.get().reason().endsWith(LINKED);
        if (!"true".equals(exported.get("mute"))) {
            if (mirrored) {
                lift(player, PunishmentKind.MUTE, null, "lifted on the linked server");
            }
            return;
        }
        String ends = exported.getOrDefault("mute.ends", "");
        Instant endsAt = ends.isBlank() ? null : Instant.ofEpochMilli(Long.parseLong(ends));
        if (endsAt != null && !endsAt.isAfter(now())) {
            return;
        }
        String given = exported.getOrDefault("mute.reason", "");
        String reason = (given.isBlank() ? "no reason given" : given.trim()) + LINKED;
        if (here.isPresent()) {
            Punishment current = here.get();
            if (mirrored && java.util.Objects.equals(current.endsAt(), endsAt) && current.reason().equals(reason)) {
                return;
            }
            boolean longer = current.endsAt() != null && (endsAt == null || endsAt.isAfter(current.endsAt()));
            if (!mirrored && !longer) {
                return;
            }
        }
        String moderator = exported.getOrDefault("mute.moderator", "");
        punish(player, PunishmentKind.MUTE, moderator.isBlank() ? null : UUID.fromString(moderator), reason,
                endsAt == null ? null : Duration.between(now(), endsAt));
    }

    /** Whether an {@link #export} says the player is banned there. */
    public static boolean banned(java.util.Map<String, String> exported) {
        return exported != null && "true".equals(exported.get("ban"));
    }

    /**
     * Ends a punishment early.
     *
     * @return whether there was one to end
     */
    public boolean lift(UUID target, PunishmentKind kind, UUID moderator, String reason) {
        if (target == null || kind == null) {
            return false;
        }
        Instant now = now();
        boolean[] lifted = new boolean[1];
        byPlayer.computeIfPresent(target, (player, old) -> {
            List<Punishment> next = new ArrayList<>(old);
            for (int at = 0; at < next.size(); at++) {
                Punishment each = next.get(at);
                if (each.kind() == kind && each.isActiveAt(now)) {
                    Punishment updated = each.lifted(moderator, reason, now);
                    next.set(at, updated);
                    // Marked inside the swap: one way of changing a punishment forgetting to mark it is a lift
                    // that applies until the next restart and then quietly comes back.
                    changed.add(updated.id());
                    lifted[0] = true;
                    break;
                }
            }
            return List.copyOf(next);
        });
        if (!lifted[0]) {
            return false;
        }
        writeSoon();
        log.info("{}'s {} was lifted by {} ('{}')", target, kind.name().toLowerCase(Locale.ROOT),
                moderator, reason);
        return true;
    }

    // ---------------------------------------------------------------------------- asking

    /** Whether this punishment applies to this player right now. */
    public boolean isActive(UUID player, PunishmentKind kind) {
        return active(player, kind).isPresent();
    }

    /** The punishment of this kind in force, if any. */
    public Optional<Punishment> active(UUID player, PunishmentKind kind) {
        if (player == null || kind == null) {
            return Optional.empty();
        }
        Instant now = now();
        return recordsOf(player).stream()
                .filter(punishment -> punishment.kind() == kind)
                .filter(punishment -> punishment.isActiveAt(now))
                .findFirst();
    }

    /** How much longer a punishment has, for telling the player. */
    public Optional<Duration> remaining(UUID player, PunishmentKind kind) {
        return active(player, kind).flatMap(punishment -> punishment.remainingAt(now()));
    }

    /** Everything that has ever happened to this player, newest first. */
    public List<Punishment> history(UUID player) {
        if (player == null) {
            return List.of();
        }
        List<Punishment> records = new ArrayList<>(recordsOf(player));
        records.sort(Comparator.comparing(Punishment::givenAt).reversed());
        return List.copyOf(records);
    }

    /** Everything in force right now, for a moderator's screen. */
    public List<Punishment> allActive() {
        Instant now = now();
        return byPlayer.values().stream()
                .flatMap(List::stream)
                .filter(punishment -> punishment.isActiveAt(now))
                .toList();
    }

    /** The same, of one kind — the ban list, the mute list. */
    public List<Punishment> allActive(PunishmentKind kind) {
        return allActive().stream().filter(punishment -> punishment.kind() == kind).toList();
    }

    private List<Punishment> recordsOf(UUID player) {
        return byPlayer.getOrDefault(player, List.of());
    }

    // ---------------------------------------------------------------------------- the file

    /** Whether anything is waiting to be written. */
    public boolean isDirty() {
        return !changed.isEmpty();
    }

    /**
     * Reads every punishment the server has ever handed out.
     *
     * <p>All of them, not only the ones in force: "has this player been banned before" is the
     * question a moderator actually asks, and it cannot be answered from a table of what applies
     * right now.
     *
     * <p>Must be called off the server's threads.
     */
    public void load() {
        byPlayer.clear();
        changed.clear();
        if (!database.isUsable()) {
            // Deliberately loud: without this table every ban on the server has silently stopped
            // applying, which somebody has to know about now rather than when a banned player
            // reappears.
            log.fatal("The punishment table is not available. Nobody is banned or muted this "
                    + "session until this is fixed.");
            return;
        }
        Map<UUID, List<Punishment>> loaded = new java.util.HashMap<>();
        int read = database.read(connection -> {
            int found = 0;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, target, kind, moderator, reason, given_at, ends_at,
                           lifter, lifter_reason, lifted_at
                    FROM punishment""");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    try {
                        Punishment punishment = readOne(rows);
                        loaded.computeIfAbsent(punishment.target(), key -> new ArrayList<>()).add(punishment);
                        found++;
                    } catch (RuntimeException broken) {
                        // One unreadable row — a kind this version no longer has, a UUID somebody
                        // edited by hand. The rest of the table is still worth having.
                        log.warn("A punishment row could not be read and was skipped ({})",
                                broken.getMessage());
                    }
                }
            }
            return found;
        }).orElse(-1);
        if (read < 0) {
            log.fatal("The punishments could not be read. Nobody is banned or muted this session "
                    + "until this is fixed.");
        }
        loaded.forEach((player, records) -> byPlayer.put(player, List.copyOf(records)));
    }

    /**
     * Reads one player's punishments from the database again — because another server said they
     * changed, or because they are joining and the answer must be the database's, not this cache's.
     *
     * <p>What this server changed and has not written yet is kept over what the database says: it is
     * newer, and dropping it would be a lift that never happened. When the database cannot be read, the
     * cache stays as it is — forgetting every ban because the network blinked is the worst answer.
     *
     * <p>Must be called off the server's threads.
     */
    public void refresh(UUID player) {
        if (player == null || !database.isUsable()) {
            return;
        }
        Optional<List<Punishment>> stored = database.read(connection -> {
            List<Punishment> found = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, target, kind, moderator, reason, given_at, ends_at,
                           lifter, lifter_reason, lifted_at
                    FROM punishment WHERE target = ?""")) {
                statement.setString(1, player.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        try {
                            found.add(readOne(rows));
                        } catch (RuntimeException broken) {
                            log.warn("A punishment row could not be read and was skipped ({})", broken.getMessage());
                        }
                    }
                }
            }
            return found;
        });
        if (stored.isEmpty()) {
            return;
        }
        takeStored(player, stored.get());
    }

    /**
     * Reads every punishment again, as {@link #refresh} does one player's — for when this server may have
     * missed what other servers announced. Off the server's threads.
     */
    public void refreshAll() {
        if (!database.isUsable()) {
            return;
        }
        Optional<Map<UUID, List<Punishment>>> stored = database.read(connection -> {
            Map<UUID, List<Punishment>> found = new java.util.HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, target, kind, moderator, reason, given_at, ends_at,
                           lifter, lifter_reason, lifted_at
                    FROM punishment""");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    try {
                        Punishment punishment = readOne(rows);
                        found.computeIfAbsent(punishment.target(), key -> new ArrayList<>()).add(punishment);
                    } catch (RuntimeException broken) {
                        log.warn("A punishment row could not be read and was skipped ({})", broken.getMessage());
                    }
                }
            }
            return found;
        });
        if (stored.isEmpty()) {
            return;
        }
        java.util.Set<UUID> everybody = new java.util.HashSet<>(byPlayer.keySet());
        everybody.addAll(stored.get().keySet());
        everybody.forEach(player -> takeStored(player, stored.get().getOrDefault(player, List.of())));
    }

    /** Takes what the database holds for a player, keeping what this server has not finished writing. */
    private void takeStored(UUID player, List<Punishment> stored) {
        byPlayer.compute(player, (key, old) -> {
            Map<String, Punishment> merged = new java.util.LinkedHashMap<>();
            stored.forEach(each -> merged.put(each.id(), each));
            if (old != null) {
                old.stream().filter(each -> changed.contains(each.id()) || inFlight.contains(each.id()))
                        .forEach(each -> merged.put(each.id(), each));
            }
            return merged.isEmpty() ? null : List.copyOf(merged.values());
        });
    }

    /**
     * Joins the network: what this server writes is announced to the others, and what they announce
     * is read again here.
     */
    public void connect(NetworkBus network) {
        this.bus = network;
        if (network != null) {
            network.subscribe(TOPIC, event -> {
                try {
                    refresh(UUID.fromString(event.key()));
                } catch (IllegalArgumentException notAPlayer) {
                    log.warn("A punishment event named no player: '{}'", event.key());
                }
            });
            network.onResync(this::refreshAll);
        }
    }

    private static Punishment readOne(ResultSet rows) throws SQLException {
        return new Punishment(
                rows.getString("id"),
                UUID.fromString(rows.getString("target")),
                PunishmentKind.valueOf(rows.getString("kind")),
                uuid(rows.getString("moderator")),
                rows.getString("reason"),
                Instant.ofEpochMilli(rows.getLong("given_at")),
                instantOrNull(rows, "ends_at"),
                uuid(rows.getString("lifter")),
                rows.getString("lifter_reason"),
                instantOrNull(rows, "lifted_at"));
    }

    /** A time column that may be absent — SQLite answers 0 for NULL, so the flag has to be read. */
    private static Instant instantOrNull(ResultSet rows, String column) throws SQLException {
        long millis = rows.getLong(column);
        return rows.wasNull() ? null : Instant.ofEpochMilli(millis);
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    /**
     * Writes whatever changed, in one transaction.
     *
     * <p>Only the rows that changed, and all of them together: a moderator extending a mute lifts
     * the old one and adds a new one, and a reader that saw one without the other would see a player
     * as unpunished for the moment in between.
     *
     * <p>Must be called off the server's threads.
     */
    public void flush() {
        synchronized (flushing) {
            writeChanges();
        }
    }

    /**
     * One flush at a time. The saving timer can still be mid-flush when shutdown flushes by hand, and
     * two at once either land out of order or answer "written" while the other still holds the rows.
     */
    private final Object flushing = new Object();

    private void writeChanges() {
        if (changed.isEmpty() || !database.isUsable()) {
            return;
        }
        // Drained rather than snapshotted — see Marks. Copying the marks and clearing them
        // afterwards loses any change that arrives while the write is running.
        Set<String> writing = Marks.drainInto(changed, inFlight);
        try {
            writeAndAnnounce(writing);
        } finally {
            inFlight.removeAll(writing);
        }
    }

    /** Ids being written right now: a refresh in that moment keeps them, as it keeps unwritten ones. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private void writeAndAnnounce(Set<String> writing) {
        List<Punishment> rows = byPlayer.values().stream()
                .flatMap(List::stream)
                .filter(punishment -> writing.contains(punishment.id()))
                .toList();

        boolean written = database.write(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO punishment (id, target, kind, moderator, reason, given_at,
                                            ends_at, lifter, lifter_reason, lifted_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        lifter        = excluded.lifter,
                        lifter_reason = excluded.lifter_reason,
                        lifted_at     = excluded.lifted_at,
                        ends_at       = excluded.ends_at""")) {
                for (Punishment punishment : rows) {
                    statement.setString(1, punishment.id());
                    statement.setString(2, punishment.target().toString());
                    statement.setString(3, punishment.kind().name());
                    statement.setString(4, asText(punishment.moderator()));
                    statement.setString(5, punishment.reason());
                    statement.setLong(6, punishment.givenAt().toEpochMilli());
                    setMillisOrNull(statement, 7, punishment.endsAt());
                    statement.setString(8, asText(punishment.lifter()));
                    statement.setString(9, punishment.lifterReason());
                    setMillisOrNull(statement, 10, punishment.liftedAt());
                    statement.executeUpdate();
                }
            }
        });
        if (!written) {
            // Put back, so the next flush tries again. A dropped mark here is a ban nothing will
            // ever write.
            Marks.restore(changed, writing);
            return;
        }
        // After the commit, never before: a server that hears it too early reads the old rows and keeps them.
        NetworkBus network = bus;
        if (network != null) {
            List<UUID> targets = rows.stream().map(Punishment::target).distinct().toList();
            inFlight.removeAll(writing);
            // Read back: anything another server wrote while this write held these rows was skipped by
            // the refresh it caused, and is picked up now.
            targets.forEach(this::refresh);
            targets.forEach(target -> network.publish(TOPIC, target.toString()));
        }
    }

    /** Hands a write over the moment something changed; the changes themselves are already marked. */
    private void writeSoon() {
        Consumer<Runnable> soon = writeSoon;
        if (soon != null) {
            soon.accept(this::flush);
        }
    }

    /** How many punishments are waiting to be written. */
    public int waitingToBeWritten() {
        return changed.size();
    }

    private static void setMillisOrNull(PreparedStatement statement, int at, Instant when)
            throws SQLException {
        if (when == null) {
            statement.setNull(at, Types.INTEGER);
        } else {
            statement.setLong(at, when.toEpochMilli());
        }
    }

    private static String asText(UUID id) {
        return id == null ? null : id.toString();
    }
}
