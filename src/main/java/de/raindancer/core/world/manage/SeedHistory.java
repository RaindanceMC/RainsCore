package de.raindancer.core.world.manage;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Every seed a world has had.
 *
 * <h2>Why anybody needs this</h2>
 * A regenerated world's old seed exists nowhere else: the {@code level.dat} it was in is the first thing
 * deleted. "The map before last had a great village" is only something a server can go back to if
 * somebody wrote the seed down before the folder went — so {@link WorldRegenerator} does, every time,
 * and this is where it goes.
 *
 * <h2>Writing</h2>
 * The same arrangement as {@code PoiStore}: kept in memory, and written by {@link #flush()} — on Core's
 * saving timer and at shutdown — never on the thread that is regenerating the world. Rows are only ever
 * added, so a flush is an insert of whatever is queued and nothing else.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread.
 */
public final class SeedHistory {

    private static final LogChannel log = Log.of("world");

    /** Why a seed was written down. */
    public enum Cause {
        /** A world was created with it. */
        CREATED,
        /** A world was regenerated with it. */
        REGENERATED,
        /** A world that had it was about to be regenerated — written before its folder is deleted. */
        REPLACED,
        /** A world that had it was deleted for good. */
        DELETED
    }

    /** One seed, for one world, at one moment. */
    public record Entry(String world, long seed, long at, Cause cause) {
    }

    private final Database database;
    private final LongSupplier clock;
    private final Consumer<Runnable> writeSoon;

    /** Newest first, per world, keyed case-insensitively the way Bukkit looks worlds up. */
    private final Map<String, List<Entry>> byWorld = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Entry> unwritten = new ConcurrentLinkedQueue<>();

    public SeedHistory(Database database, LongSupplier clock) {
        this(database, clock, null);
    }

    /**
     * @param writeSoon handed a flush to run off the server's threads whenever a seed is recorded — Core
     *                  passes its async scheduler. Seeds are recorded rarely and matter much, so waiting
     *                  for the saving timer only leaves them to be written at shutdown, which is on the
     *                  thread running the world. Null leaves writing to whoever calls {@link #flush()}.
     */
    public SeedHistory(Database database, LongSupplier clock, Consumer<Runnable> writeSoon) {
        this.database = database;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.writeSoon = writeSoon;
    }

    /** Reads every recorded seed. Must be called off the server's threads, like every other load. */
    public void load() {
        byWorld.clear();
        if (database == null) {
            return;
        }
        List<Entry> rows = database.read(connection -> {
            List<Entry> read = new ArrayList<>();
            // This server's seeds, and the unstamped rows a 1.x server wrote — which were this server's too.
            String here = de.raindancer.core.platform.network.ThisServer.id();
            try (PreparedStatement statement = connection.prepareStatement(here.isBlank()
                    ? "SELECT world, seed, at, cause FROM world_seed ORDER BY at, id"
                    : "SELECT world, seed, at, cause FROM world_seed WHERE server IN (?, '') ORDER BY at, id")) {
                if (!here.isBlank()) {
                    statement.setString(1, here);
                }
                try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Cause cause;
                    try {
                        cause = Cause.valueOf(result.getString("cause"));
                    } catch (IllegalArgumentException | NullPointerException unknown) {
                        // A cause a newer Core wrote. The seed is the part worth keeping.
                        cause = Cause.CREATED;
                    }
                    read.add(new Entry(result.getString("world"), result.getLong("seed"),
                            result.getLong("at"), cause));
                }
                }
            }
            return read;
        }).orElse(List.of());
        rows.forEach(this::remember);
    }

    /**
     * Writes a seed down.
     *
     * @return whether anything was recorded — a blank world name is not a world
     */
    public boolean record(String world, long seed, Cause cause) {
        if (world == null || world.isBlank()) {
            return false;
        }
        Entry entry = new Entry(world, seed, clock.getAsLong(), cause == null ? Cause.CREATED : cause);
        remember(entry);
        unwritten.add(entry);
        if (writeSoon != null) {
            writeSoon.accept(this::flush);
        }
        return true;
    }

    private void remember(Entry entry) {
        byWorld.compute(key(entry.world()), (key, before) -> {
            List<Entry> after = before == null ? new ArrayList<>() : new ArrayList<>(before);
            after.add(entry);
            after.sort(Comparator.comparingLong(Entry::at).reversed());
            return List.copyOf(after);
        });
    }

    /** Every seed this world has had, newest first. */
    public List<Entry> of(String world) {
        return world == null ? List.of() : byWorld.getOrDefault(key(world), List.of());
    }

    /**
     * Each distinct seed once, at the last time it was recorded, newest first — what somebody choosing
     * a seed to go back to wants to read, rather than the same seed listed on either side of every
     * regeneration.
     */
    public List<Entry> seeds(String world) {
        Map<Long, Entry> newest = new LinkedHashMap<>();
        for (Entry entry : of(world)) {
            newest.putIfAbsent(entry.seed(), entry);
        }
        return List.copyOf(newest.values());
    }

    /** The seed recorded most recently for this world. */
    public OptionalLong latest(String world) {
        List<Entry> entries = of(world);
        return entries.isEmpty() ? OptionalLong.empty() : OptionalLong.of(entries.getFirst().seed());
    }

    /** Every world anything has been recorded for, under the name it was last recorded with. */
    public List<String> worlds() {
        return byWorld.values().stream()
                .filter(entries -> !entries.isEmpty())
                .map(entries -> entries.getFirst().world())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /**
     * Writes what is queued on the writer this history was given, and answers once it is on disk — what
     * anything about to delete a world waits for, since the seed it just recorded exists nowhere else.
     * Without a writer it runs here.
     *
     * @return true once everything is written; false when it could not be
     */
    public CompletableFuture<Boolean> flushNow() {
        if (writeSoon == null) {
            return CompletableFuture.completedFuture(flush());
        }
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        writeSoon.accept(() -> {
            try {
                done.complete(flush());
            } catch (RuntimeException failure) {
                done.completeExceptionally(failure);
            }
        });
        return done;
    }

    /** Whether anything is waiting to be written. */
    public boolean isDirty() {
        return !unwritten.isEmpty();
    }

    /**
     * Writes whatever has been recorded since the last flush.
     *
     * <p>One flush at a time. A second one arriving while the first holds the queued seeds in hand
     * would find the queue empty and answer "written" — and {@link WorldRegenerator} deletes a world
     * on that answer.
     *
     * @return whether everything is on disk; a failed write keeps the rows queued for the next one
     */
    public synchronized boolean flush() {
        if (unwritten.isEmpty()) {
            return true;
        }
        if (database == null || !database.isUsable()) {
            return false;
        }
        // Drained first, so a seed recorded during the write is queued for the next flush rather
        // than lost — the same reasoning PoiStore#flush spells out.
        List<Entry> writing = new ArrayList<>();
        for (Entry next = unwritten.poll(); next != null; next = unwritten.poll()) {
            writing.add(next);
        }
        boolean written = database.write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO world_seed (world, seed, at, cause, server) VALUES (?, ?, ?, ?, ?)")) {
                for (Entry entry : writing) {
                    insert.setString(1, entry.world());
                    insert.setLong(2, entry.seed());
                    insert.setLong(3, entry.at());
                    insert.setString(4, entry.cause().name());
                    insert.setString(5, de.raindancer.core.platform.network.ThisServer.id());
                    insert.executeUpdate();
                }
            }
        });
        if (!written) {
            unwritten.addAll(writing);
            log.warn("{} world seed(s) could not be written yet; they will be tried again.",
                    writing.size());
        }
        return written;
    }

    private static String key(String world) {
        return world.toLowerCase(Locale.ROOT);
    }

}
