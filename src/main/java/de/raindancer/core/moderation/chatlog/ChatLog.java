package de.raindancer.core.moderation.chatlog;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.sql.Schema;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Everything everybody said in chat, kept for staff to read back and search, per player.
 *
 * <h2>Personal data</h2>
 * Names beside what people said: kept for moderation and for no longer than the owner's retention
 * ({@code chat-log-retention-days}), and players are told it is kept. Private messages are not recorded here.
 *
 * <h2>Threads</h2>
 * {@link #record} is called from the chat thread and only queues; reading, flushing and forgetting touch the
 * database and belong off the server's threads.
 */
public final class ChatLog {

    private static final LogChannel log = Log.of("chatlog");

    /** The channel public chat is recorded under. */
    public static final String PUBLIC = "public";

    public static final Schema SCHEMA = Schema.of(
            "CREATE TABLE chat_line (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, player TEXT NOT NULL, "
                    + "name TEXT NOT NULL, channel TEXT NOT NULL, text TEXT NOT NULL)",
            "CREATE INDEX chat_line_player ON chat_line (player, id)",
            "CREATE INDEX chat_line_at ON chat_line (at)",
            // Who used a name, for finding somebody by a name they have since changed.
            "CREATE INDEX chat_line_name ON chat_line (name COLLATE NOCASE)",
            // 2.0.0: the same question on Postgres, which has no NOCASE, is asked as lower(name).
            "CREATE INDEX chat_line_name_lower ON chat_line (lower(name))");

    /** One line, as it was said. */
    public record Line(long at, UUID player, String name, String channel, String text) {
    }

    /**
     * What staff look for: every part optional, all of them narrowing together.
     *
     * <p>Kept as a plain {@code LIKE} over the time and player indexes rather than a full-text index: a busy
     * year is well under a million short lines, which SQLite scans in a fraction of a second off the server's
     * threads, while FTS would double the file and could not find part of a word shorter than three letters.
     *
     * @param players whose lines; empty for everybody
     * @param from    from this moment on, inclusive (epoch millis)
     * @param to      before this moment, exclusive (epoch millis)
     * @param terms   each must appear in the line, in any case — a term with spaces is a phrase, as it stands
     */
    public record Search(Set<UUID> players, long from, long to, List<String> terms) {

        public Search {
            players = players == null ? Set.of() : Set.copyOf(players);
            terms = terms == null ? List.of() : terms.stream().filter(term -> term != null && !term.isBlank())
                    .map(String::strip).toList();
        }

        public static Search everything() {
            return new Search(Set.of(), Long.MIN_VALUE, Long.MAX_VALUE, List.of());
        }

        public Search by(Collection<UUID> who) {
            return new Search(who == null ? Set.of() : Set.copyOf(who), from, to, terms);
        }

        public Search between(long fromInclusive, long toExclusive) {
            return new Search(players, fromInclusive, toExclusive, terms);
        }

        public Search containing(String... wanted) {
            List<String> all = new ArrayList<>(terms);
            all.addAll(List.of(wanted));
            return new Search(players, from, to, all);
        }
    }

    private static final List<Consumer<Line>> watchers = new CopyOnWriteArrayList<>();

    private final Database database;
    private final LongSupplier clock;
    private final List<Line> pending = new ArrayList<>();
    private volatile boolean enabled = true;

    public ChatLog(Database database, LongSupplier clock) {
        this.database = database;
        this.clock = clock;
    }

    public void enabled(boolean on) {
        this.enabled = on;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Anything that wants every line as it is said — moderation's automatic flags. Called on the chat thread. */
    public static void watch(Consumer<Line> watcher) {
        if (watcher != null && !watchers.contains(watcher)) {
            watchers.add(watcher);
        }
    }

    public static void unwatch(Consumer<Line> watcher) {
        watchers.remove(watcher);
    }

    public static int forgetFrom(ClassLoader loader) {
        int before = watchers.size();
        watchers.removeIf(watcher -> de.raindancer.core.platform.util.PluginCode.isFrom(watcher, loader));
        return before - watchers.size();
    }

    public static void clearWatchers() {
        watchers.clear();
    }

    /** Somebody said this. Cheap: queued, written by the next {@link #flush}. */
    public void record(UUID player, String name, String channel, String text) {
        if (!enabled || player == null || text == null || text.isBlank()) {
            return;
        }
        Line line = new Line(clock.getAsLong(), player, name == null ? "" : name,
                channel == null || channel.isBlank() ? PUBLIC : channel, text);
        synchronized (pending) {
            pending.add(line);
        }
        for (Consumer<Line> watcher : watchers) {
            try {
                watcher.accept(line);
            } catch (RuntimeException broken) {
                log.error(broken, "Something watching chat failed on a line.");
            }
        }
    }

    /** Writes what is queued. Off the server's threads. @return how many lines were written */
    public int flush() {
        List<Line> writing;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return 0;
            }
            writing = List.copyOf(pending);
            pending.clear();
        }
        boolean written = database.write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO chat_line (at, player, name, channel, text) VALUES (?, ?, ?, ?, ?)")) {
                for (Line line : writing) {
                    insert.setLong(1, line.at());
                    insert.setString(2, line.player().toString());
                    insert.setString(3, line.name());
                    insert.setString(4, line.channel());
                    insert.setString(5, line.text());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
        if (!written) {
            synchronized (pending) {
                pending.addAll(0, writing);
            }
            return 0;
        }
        return writing.size();
    }

    /**
     * What somebody said, newest first. Off the server's threads.
     *
     * @param search words that must all appear, in any case; blank for everything
     */
    public List<Line> of(UUID player, String search, int limit, int offset) {
        return search(Search.everything().by(Set.of(player)).containing(words(search).toArray(String[]::new)),
                limit, offset);
    }

    /** What matches, newest first. Off the server's threads. */
    public List<Line> search(Search what, int limit, int offset) {
        flush();
        Where where = where(what, database.dialect());
        String sql = "SELECT at, player, name, channel, text FROM chat_line" + where.sql
                + " ORDER BY id DESC LIMIT ? OFFSET ?";
        return database.read(connection -> {
            List<Line> found = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                int at = where.bind(select);
                select.setInt(at++, Math.max(1, limit));
                select.setInt(at, Math.max(0, offset));
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        found.add(new Line(rows.getLong(1), UUID.fromString(rows.getString(2)), rows.getString(3),
                                rows.getString(4), rows.getString(5)));
                    }
                }
            }
            return found;
        }).orElse(List.of());
    }

    /** How many lines match. Off the server's threads. */
    public long count(Search what) {
        flush();
        Where where = where(what, database.dialect());
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("SELECT COUNT(*) FROM chat_line" + where.sql)) {
                where.bind(select);
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? rows.getLong(1) : 0L;
                }
            }
        }).orElse(0L);
    }

    /** Everybody who has said something under this name, in any case — old names included. Off the server's threads. */
    public Set<UUID> playersNamed(String name) {
        if (name == null || name.isBlank()) {
            return Set.of();
        }
        flush();
        return database.read(connection -> {
            Set<UUID> found = new LinkedHashSet<>();
            try (PreparedStatement select = connection.prepareStatement(
                    database.dialect() == de.raindancer.core.data.sql.Dialect.POSTGRESQL
                            ? "SELECT DISTINCT player FROM chat_line WHERE lower(name) = lower(?)"
                            : "SELECT DISTINCT player FROM chat_line WHERE name = ? COLLATE NOCASE")) {
                select.setString(1, name.strip());
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        found.add(UUID.fromString(rows.getString(1)));
                    }
                }
            }
            return (Set<UUID>) found;
        }).orElse(Set.of());
    }

    /** Every name somebody chatted under, the one used most recently first. Off the server's threads. */
    public List<String> namesOf(UUID player) {
        if (player == null) {
            return List.of();
        }
        flush();
        return database.read(connection -> {
            List<String> found = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT name FROM chat_line WHERE player = ? GROUP BY name ORDER BY MAX(id) DESC")) {
                select.setString(1, player.toString());
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        found.add(rows.getString(1));
                    }
                }
            }
            return found;
        }).orElse(List.of());
    }

    private record Where(String sql, List<Object> values) {
        int bind(PreparedStatement statement) throws java.sql.SQLException {
            int at = 1;
            for (Object value : values) {
                statement.setObject(at++, value);
            }
            return at;
        }
    }

    private static Where where(Search what, de.raindancer.core.data.sql.Dialect dialect) {
        Search search = what == null ? Search.everything() : what;
        List<String> parts = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (!search.players().isEmpty()) {
            parts.add("player IN (" + String.join(", ", java.util.Collections.nCopies(search.players().size(), "?")) + ")");
            search.players().forEach(player -> values.add(player.toString()));
        }
        if (search.from() != Long.MIN_VALUE) {
            parts.add("at >= ?");
            values.add(search.from());
        }
        if (search.to() != Long.MAX_VALUE) {
            parts.add("at < ?");
            values.add(search.to());
        }
        for (String term : search.terms()) {
            // LIKE folds ASCII case only; a term with other letters is also tried in the cases people write it.
            Set<String> spellings = new LinkedHashSet<>();
            spellings.add(term.toLowerCase(Locale.ROOT));
            if (!term.chars().allMatch(c -> c < 128)) {
                String lower = term.toLowerCase(Locale.ROOT);
                spellings.add(lower.toUpperCase(Locale.ROOT));
                spellings.add(lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1));
            }
            parts.add("(" + String.join(" OR ", java.util.Collections.nCopies(spellings.size(),
                    "text " + dialect.likeIgnoringCase() + " ? ESCAPE '\\'")) + ")");
            spellings.forEach(spelling -> values.add("%" + escaped(spelling) + "%"));
        }
        return new Where(parts.isEmpty() ? "" : " WHERE " + String.join(" AND ", parts), values);
    }

    /** How many lines of theirs are kept. Off the server's threads. */
    public long count(UUID player) {
        flush();
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT COUNT(*) FROM chat_line WHERE player = ?")) {
                select.setString(1, player.toString());
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? rows.getLong(1) : 0L;
                }
            }
        }).orElse(0L);
    }

    /** Deletes what is older than the retention. Off the server's threads. @return how many lines went */
    public int forgetOlderThan(Duration age) {
        flush();
        long cutoff = clock.getAsLong() - age.toMillis();
        int[] deleted = {0};
        database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM chat_line WHERE at < ?")) {
                delete.setLong(1, cutoff);
                deleted[0] = delete.executeUpdate();
            }
        });
        return deleted[0];
    }

    private static List<String> words(String search) {
        if (search == null || search.isBlank()) {
            return List.of();
        }
        return List.of(search.strip().toLowerCase(Locale.ROOT).split("\\s+"));
    }

    private static String escaped(String word) {
        return word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
