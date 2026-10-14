package de.raindancer.core.data.runs;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Marks;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Every finished run of one game, ranked: leaderboards, personal bests, records, splits.
 *
 * <pre>{@code
 * RunHistory history = core.runHistory("speedrun");          // one per game, shared and kept by Core
 * Standing how = history.add(Run.timed("manhunt/1v3", took)
 *         .player(runner.getUniqueId(), runner.getName())
 *         .split("nether", netherAt)
 *         .field("seed", seed)
 *         .build());
 * if (how.newRecord()) { ... } else if (how.isPersonalBest(runner.getUniqueId())) { ... }
 * history.leaderboard("manhunt/1v3", Board.onePerPlayer().top(10));
 * }</pre>
 *
 * <h2>Several modes in one game</h2>
 * A run's category is the plugin's own string — {@code "speedrun/any%"}, {@code "manhunt/1v3"} — so one
 * plugin with several modes keeps them apart in one history, and {@link #categories(String)} lists one
 * mode's. Runs only ever rank against their own category.
 *
 * <h2>Kept, never lost</h2>
 * In memory for asking, in Core's database for keeping (written as each run is added, off the server's
 * threads). A database that could not be opened is never written over: runs added meanwhile are kept
 * in memory, {@link #isWritable()} says so, and they are written the moment it works again. Nothing is
 * ever deleted — a run struck from the rankings ({@link #rank(String, boolean)}) stays in the history.
 *
 * <h2>Threads</h2>
 * Safe from any thread. {@link #load()} and {@link #flush()} touch the database and belong off the
 * server's threads.
 */
public final class RunHistory {

    private static final LogChannel log = Log.of("runs");

    /** How a leaderboard is drawn. */
    public record Board(boolean bestPerPlayer, int limit, Predicate<Run> filter) {

        public Board {
            limit = limit <= 0 ? Integer.MAX_VALUE : limit;
            filter = filter == null ? run -> true : filter;
        }

        /** Every ranked run, best first. */
        public static Board everyRun() {
            return new Board(false, 0, null);
        }

        /** Each player's best only — the usual board for solo categories. */
        public static Board onePerPlayer() {
            return new Board(true, 0, null);
        }

        public Board top(int howMany) {
            return new Board(bestPerPlayer, howMany, filter);
        }

        /** Only runs that also pass this — a player count, a seed, a season. */
        public Board where(Predicate<Run> also) {
            return new Board(bestPerPlayer, limit, also == null ? filter : filter.and(also));
        }
    }

    /**
     * Where a newly added run stands — worked out against the history as it was before it.
     *
     * @param rank               its place among every ranked run of its category, 1 for the best; 0 when
     *                           it is not ranked
     * @param recordBefore       the category's record before this run
     * @param personalBestsBefore each player's best in the category before this run
     */
    public record Standing(Run run, int rank, Optional<Run> recordBefore,
                           Map<UUID, Optional<Run>> personalBestsBefore) {

        /** Whether it is the category's new record — the first ranked run counts. */
        public boolean newRecord() {
            return run.ranked() && recordBefore.map(run::beats).orElse(true);
        }

        /** Whether it is this player's new personal best. */
        public boolean isPersonalBest(UUID player) {
            return run.ranked() && run.includes(player)
                    && personalBestsBefore.getOrDefault(player, Optional.empty()).map(run::beats).orElse(true);
        }

        /** By how much it beat (negative) or missed (positive) the record before it, in score units. */
        public Optional<Long> versusRecord() {
            return recordBefore.map(record -> run.lowerWins() ? run.score() - record.score()
                    : record.score() - run.score());
        }

        /** The same against a player's own best before it. */
        public Optional<Long> versusPersonalBest(UUID player) {
            return personalBestsBefore.getOrDefault(player, Optional.empty())
                    .map(best -> run.lowerWins() ? run.score() - best.score() : best.score() - run.score());
        }
    }

    private final Database database;
    private final String game;
    private final List<Run> runs = new CopyOnWriteArrayList<>();
    private final Set<String> unwritten = ConcurrentHashMap.newKeySet();
    private final Object flushing = new Object();
    private volatile Consumer<Runnable> writeSoon;
    private volatile boolean loaded;

    /**
     * @param game the game's own name — usually the plugin's — so two games never see each other's runs
     */
    public RunHistory(Database database, String game) {
        this.database = database;
        this.game = Objects.requireNonNull(game, "game").trim().toLowerCase(Locale.ROOT);
    }

    public String game() {
        return game;
    }

    /** Has every new run written straight away through {@code writeSoon} — Core passes its async scheduler. */
    public void writeSoon(Consumer<Runnable> writeSoon) {
        this.writeSoon = writeSoon;
    }

    // ---------------------------------------------------------------------------- loading

    /** Reads every run of this game. Off the server's threads, once, at start-up. */
    public void load() {
        if (database == null || !database.isUsable()) {
            log.warn("The run history of {} could not be read: its database is not available. New runs "
                    + "are kept and written once it is.", game);
            return;
        }
        Optional<List<Run>> read = database.read(connection -> readAll(connection, null));
        if (read.isEmpty()) {
            log.warn("The run history of {} could not be read; it was left exactly as it is.", game);
            return;
        }
        Map<String, Run> merged = new LinkedHashMap<>();
        read.get().forEach(run -> merged.put(run.id(), run));
        // Runs added before the load finished are kept, and win over their stored copy.
        runs.forEach(run -> merged.put(run.id(), run));
        runs.clear();
        runs.addAll(merged.values());
        loaded = true;
    }

    /** Every run of this game, or the one with {@code onlyId}. */
    private List<Run> readAll(Connection connection, String onlyId) throws SQLException {
        String one = onlyId == null ? "" : " AND r.id = ?";
        Map<String, Map<UUID, String>> players = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT p.run, p.player, p.name FROM run_player p JOIN run r ON r.id = p.run WHERE r.game = ?" + one)) {
            statement.setString(1, game);
            if (onlyId != null) {
                statement.setString(2, onlyId);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    try {
                        players.computeIfAbsent(rows.getString(1), key -> new LinkedHashMap<>())
                                .put(UUID.fromString(rows.getString(2)), rows.getString(3));
                    } catch (IllegalArgumentException notAnId) {
                        log.warn("A player of run {} is not a player id; skipped.", rows.getString(1));
                    }
                }
            }
        }
        Map<String, Map<String, Long>> splits = new HashMap<>();
        Map<String, Map<String, String>> fields = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT v.run, v.kind, v.name, v.value FROM run_value v JOIN run r ON r.id = v.run WHERE r.game = ?" + one)) {
            statement.setString(1, game);
            if (onlyId != null) {
                statement.setString(2, onlyId);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String run = rows.getString(1);
                    if ("split".equals(rows.getString(2))) {
                        try {
                            splits.computeIfAbsent(run, key -> new LinkedHashMap<>())
                                    .put(rows.getString(3), Long.parseLong(rows.getString(4)));
                        } catch (NumberFormatException notATime) {
                            log.warn("Split '{}' of run {} is not a time; skipped.", rows.getString(3), run);
                        }
                    } else {
                        fields.computeIfAbsent(run, key -> new LinkedHashMap<>())
                                .put(rows.getString(3), rows.getString(4));
                    }
                }
            }
        }
        List<Run> read = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, category, started_at, score, lower_wins, ranked FROM run r WHERE game = ?" + one)) {
            statement.setString(1, game);
            if (onlyId != null) {
                statement.setString(2, onlyId);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString("id");
                    read.add(new Run(id, rows.getString("category"),
                            Instant.ofEpochMilli(rows.getLong("started_at")), rows.getLong("score"),
                            rows.getBoolean("lower_wins"), rows.getBoolean("ranked"),
                            players.get(id), splits.get(id), fields.get(id)));
                }
            }
        }
        return read;
    }

    // ---------------------------------------------------------------------------- adding

    /**
     * Records a finished run and says where it stands. Adding the same id again replaces it.
     */
    public Standing add(Run run) {
        Objects.requireNonNull(run, "run");
        Standing standing = standingOf(run);
        runs.removeIf(existing -> existing.id().equals(run.id()));
        runs.add(run);
        changed(run.id());
        return standing;
    }

    /** Where a run would stand, without adding it — "what would this time have been worth". */
    public Standing standingOf(Run run) {
        List<Run> before = ranked(run.category(), other -> !other.id().equals(run.id()));
        Optional<Run> record = before.stream().min(Run::bestFirst);
        Map<UUID, Optional<Run>> bests = new LinkedHashMap<>();
        for (UUID player : run.players().keySet()) {
            bests.put(player, before.stream().filter(other -> other.includes(player)).min(Run::bestFirst));
        }
        int rank = 0;
        if (run.ranked()) {
            rank = 1 + (int) before.stream().filter(other -> Run.bestFirst(other, run) < 0).count();
        }
        return new Standing(run, rank, record, bests);
    }

    /**
     * Strikes a run from the rankings, or puts it back. Struck runs stay in the history and in
     * {@link #runsOf}, but never count as a best or a record.
     *
     * @return whether there was such a run
     */
    public boolean rank(String id, boolean ranked) {
        for (Run run : runs) {
            if (run.id().equals(id)) {
                runs.remove(run);
                runs.add(run.ranked(ranked));
                changed(id);
                return true;
            }
        }
        return false;
    }

    private void changed(String id) {
        unwritten.add(id);
        Consumer<Runnable> soon = writeSoon;
        if (soon != null) {
            soon.accept(this::flush);
        }
    }

    // ---------------------------------------------------------------------------- asking

    /** The ranked runs of a category, drawn as asked. */
    public List<Run> leaderboard(String category, Board board) {
        Board how = board == null ? Board.everyRun() : board;
        List<Run> ranked = new ArrayList<>(ranked(category, how.filter()));
        ranked.sort(Run::bestFirst);
        if (how.bestPerPlayer()) {
            Set<UUID> seen = new LinkedHashSet<>();
            List<Run> best = new ArrayList<>();
            for (Run run : ranked) {
                boolean someoneNew = run.players().keySet().stream().anyMatch(player -> !seen.contains(player));
                if (someoneNew || run.players().isEmpty()) {
                    best.add(run);
                    seen.addAll(run.players().keySet());
                }
            }
            ranked = best;
        }
        return ranked.size() > how.limit() ? List.copyOf(ranked.subList(0, how.limit())) : List.copyOf(ranked);
    }

    /** The category's best ranked run. */
    public Optional<Run> record(String category) {
        return ranked(category, run -> true).stream().min(Run::bestFirst);
    }

    /** A player's best ranked run in a category. */
    public Optional<Run> personalBest(UUID player, String category) {
        return ranked(category, run -> run.includes(player)).stream().min(Run::bestFirst);
    }

    /** A player's place on a category's one-per-player board, 1 for the best; empty when not on it. */
    public Optional<Integer> placeOf(UUID player, String category) {
        List<Run> board = leaderboard(category, Board.onePerPlayer());
        for (int at = 0; at < board.size(); at++) {
            if (board.get(at).includes(player)) {
                return Optional.of(at + 1);
            }
        }
        return Optional.empty();
    }

    /** The best time anybody reached a named split in a category, among ranked runs. */
    public Optional<Duration> bestSplit(String category, String split) {
        return ranked(category, run -> true).stream()
                .map(run -> run.split(split))
                .flatMap(Optional::stream)
                .min(Comparator.naturalOrder());
    }

    /** Every run a player took part in, newest first — struck ones too. */
    public List<Run> runsOf(UUID player) {
        return runs.stream().filter(run -> run.includes(player))
                .sorted(Comparator.comparing(Run::startedAt).reversed()).toList();
    }

    /** Every run, newest first. */
    public List<Run> newestFirst() {
        return runs.stream().sorted(Comparator.comparing(Run::startedAt).reversed()).toList();
    }

    public Optional<Run> byId(String id) {
        return runs.stream().filter(run -> run.id().equals(id)).findFirst();
    }

    /** Every category with a run in it, the most played first. */
    public List<String> categories() {
        return categories("");
    }

    /** The same for one mode — every category starting with {@code prefix}, {@code "manhunt/"}. */
    public List<String> categories(String prefix) {
        String wanted = prefix == null ? "" : prefix;
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Run run : runs) {
            if (run.category().startsWith(wanted)) {
                counts.merge(run.category(), 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).toList();
    }

    public int size() {
        return runs.size();
    }

    /** Whether runs are being written — false while the database cannot be used; nothing is lost. */
    public boolean isWritable() {
        return database != null && database.isUsable();
    }

    /** Whether the stored history has been read. */
    public boolean isLoaded() {
        return loaded;
    }

    /** How many changes are waiting to be written. */
    public int waiting() {
        return unwritten.size();
    }

    private List<Run> ranked(String category, Predicate<Run> also) {
        return runs.stream().filter(Run::ranked).filter(run -> run.category().equals(category))
                .filter(also).toList();
    }

    // ---------------------------------------------------------------------------- writing

    /**
     * Writes what changed since the last write.
     *
     * @return whether everything is on disk; a failed write keeps it waiting for the next
     */
    public boolean flush() {
        synchronized (flushing) {
            if (unwritten.isEmpty()) {
                return true;
            }
            if (!isWritable()) {
                return false;
            }
            Set<String> writing = Marks.drain(unwritten);
            inFlight.addAll(writing);
            try {
                return writeAndAnnounce(writing);
            } finally {
                inFlight.removeAll(writing);
            }
        }
    }

    /** Runs being written right now: a refresh in that moment keeps them, as it keeps unwritten ones. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private boolean writeAndAnnounce(Set<String> writing) {
        List<Run> rows = runs.stream().filter(run -> writing.contains(run.id())).toList();
        boolean written = database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO run (id, game, category, started_at, score, lower_wins, ranked)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET category = excluded.category,
                        started_at = excluded.started_at, score = excluded.score,
                        lower_wins = excluded.lower_wins, ranked = excluded.ranked""");
                 PreparedStatement clearPlayers = connection.prepareStatement("DELETE FROM run_player WHERE run = ?");
                 PreparedStatement clearValues = connection.prepareStatement("DELETE FROM run_value WHERE run = ?");
                 PreparedStatement player = connection.prepareStatement(
                         "INSERT INTO run_player (run, player, name) VALUES (?, ?, ?)");
                 PreparedStatement value = connection.prepareStatement(
                         "INSERT INTO run_value (run, kind, name, value) VALUES (?, ?, ?, ?)")) {
                for (Run run : rows) {
                    upsert.setString(1, run.id());
                    upsert.setString(2, game);
                    upsert.setString(3, run.category());
                    upsert.setLong(4, run.startedAt().toEpochMilli());
                    upsert.setLong(5, run.score());
                    upsert.setInt(6, run.lowerWins() ? 1 : 0);
                    upsert.setInt(7, run.ranked() ? 1 : 0);
                    upsert.executeUpdate();
                    clearPlayers.setString(1, run.id());
                    clearPlayers.executeUpdate();
                    clearValues.setString(1, run.id());
                    clearValues.executeUpdate();
                    for (Map.Entry<UUID, String> who : run.players().entrySet()) {
                        player.setString(1, run.id());
                        player.setString(2, who.getKey().toString());
                        player.setString(3, who.getValue());
                        player.executeUpdate();
                    }
                    for (Map.Entry<String, Long> split : run.splits().entrySet()) {
                        insertValue(value, run.id(), "split", split.getKey(), String.valueOf(split.getValue()));
                    }
                    for (Map.Entry<String, String> field : run.fields().entrySet()) {
                        insertValue(value, run.id(), "field", field.getKey(), field.getValue());
                    }
                }
            }
        });
        if (!written) {
            Marks.restore(unwritten, writing);
            log.warn("{} run(s) of {} could not be written yet; they will be tried again.",
                    writing.size(), game);
            return false;
        }
        de.raindancer.core.platform.network.NetworkBus network = bus;
        if (network != null) {
            writing.forEach(id -> network.publish(topic(), id));
        }
        return true;
    }

    // ---------------------------------------------------------------------------- the network

    private volatile de.raindancer.core.platform.network.NetworkBus bus;

    /** The topic other servers are told on — one per game — keyed by the run's id. */
    public String topic() {
        return "runs:" + game;
    }

    /** Joins the network: a run finished on one server is on the leaderboard of every server. */
    public void connect(de.raindancer.core.platform.network.NetworkBus network) {
        this.bus = network;
        if (network != null) {
            network.subscribe(topic(), event -> refresh(event.key()));
        }
    }

    /**
     * Reads one run again. An unwritten change of this server's wins; an unreadable database leaves what
     * is known; a run gone from the database is gone here. Off the server's threads.
     */
    public void refresh(String id) {
        if (id == null || id.isBlank() || database == null || !database.isUsable()) {
            return;
        }
        Optional<List<Run>> read = database.read(connection -> readAll(connection, id));
        if (read.isEmpty() || unwritten.contains(id) || inFlight.contains(id)) {
            return;
        }
        synchronized (runs) {
            if (!unwritten.contains(id) && !inFlight.contains(id)) {
                runs.removeIf(run -> run.id().equals(id));
                runs.addAll(read.get());
            }
        }
    }

    private static void insertValue(PreparedStatement value, String run, String kind, String name, String text)
            throws SQLException {
        value.setString(1, run);
        value.setString(2, kind);
        value.setString(3, name);
        value.setString(4, text);
        value.executeUpdate();
    }
}
