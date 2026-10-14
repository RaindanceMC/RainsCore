package de.raindancer.core.ui.identity;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Marks;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Who goes by what — kept across restarts, so {@code /heal Lilly_Pad} finds the player everybody knows
 * as Lilly Pad whether or not she is online, and tab completion offers her by that name.
 *
 * <h2>Why not {@link Identities#setNickname}</h2>
 * That one is what is <em>drawn</em> this session, handed over again by the owning plugin on every join.
 * This is the directory a command looks names up in, and a command is aimed at offline players too —
 * so it has to outlive the session. The owning plugin writes both.
 *
 * <h2>The typed form</h2>
 * A command argument is one word, so a nickname with spaces is typed with underscores. Lower-cased for
 * the lookup, case kept for the suggestion — the player sees the nickname as it was written.
 */
public final class Nicknames {

    private static final LogChannel log = Log.of("identity");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** What one player goes by: as written, and as a command would spell it. */
    private record Entry(String plain, String typed) {
    }

    private final Database database;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Set<UUID> changed = ConcurrentHashMap.newKeySet();
    private final Object flushing = new Object();

    public Nicknames(Database database) {
        this.database = database;
    }

    /** {@code "Lilly  Pad"} → {@code "lilly_pad"}: how a lookup compares. Empty for nothing. */
    public static String typed(String plain) {
        return suggestion(plain).toLowerCase(Locale.ROOT);
    }

    /** {@code "Lilly Pad"} → {@code "Lilly_Pad"}: one word, case kept, for tab completion. */
    public static String suggestion(String plain) {
        if (plain == null) {
            return "";
        }
        return SPACES.matcher(plain.strip()).replaceAll("_");
    }

    // ------------------------------------------------------------------ writing

    /** Remembers what {@code player} goes by. Blank forgets. */
    public void remember(UUID player, String plain) {
        if (player == null) {
            return;
        }
        if (plain == null || plain.isBlank()) {
            clear(player);
            return;
        }
        String stripped = plain.strip();
        entries.put(player, new Entry(stripped, typed(stripped)));
        changed.add(player);
    }

    public void clear(UUID player) {
        if (player != null && entries.remove(player) != null) {
            changed.add(player);
        }
    }

    // ------------------------------------------------------------------ reading

    public Optional<String> of(UUID player) {
        Entry entry = player == null ? null : entries.get(player);
        return entry == null ? Optional.empty() : Optional.of(entry.plain());
    }

    /**
     * Whose nickname {@code text} is, typed with underscores or spaces, in any case.
     *
     * <p>Nobody when two people share it: acting on one of them at random is worse than saying
     * "who?", and {@link #ownersOf} is there for a caller that wants to say which two.
     */
    public Optional<UUID> ownerOf(String text) {
        List<UUID> owners = ownersOf(text);
        return owners.size() == 1 ? Optional.of(owners.get(0)) : Optional.empty();
    }

    public List<UUID> ownersOf(String text) {
        String wanted = typed(text);
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<UUID> owners = new ArrayList<>(1);
        entries.forEach((player, entry) -> {
            if (entry.typed().equals(wanted)) {
                owners.add(player);
            }
        });
        return owners;
    }

    /** The nicknames, in their typed form, that begin with {@code typed} — of the players {@code include} lets through. */
    public List<String> suggest(String typed, Predicate<UUID> include) {
        String start = typed(typed);
        List<String> found = new ArrayList<>();
        entries.forEach((player, entry) -> {
            if (entry.typed().startsWith(start) && (include == null || include.test(player))) {
                found.add(suggestion(entry.plain()));
            }
        });
        found.sort(String.CASE_INSENSITIVE_ORDER);
        return found;
    }

    public int count() {
        return entries.size();
    }

    // ------------------------------------------------------------------ storage

    public void load() {
        entries.clear();
        changed.clear();
        if (!database.isUsable()) {
            log.error("The nickname table is not available; commands only know real names this session.");
            return;
        }
        boolean read = database.read(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT player, plain, typed FROM nickname");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String player = rows.getString("player");
                    try {
                        entries.put(UUID.fromString(player),
                                new Entry(rows.getString("plain"), rows.getString("typed")));
                    } catch (RuntimeException broken) {
                        log.warn("The nickname of '{}' could not be read and was skipped ({})",
                                player, broken.getMessage());
                    }
                }
            }
            return true;
        }).orElse(false);
        if (!read) {
            log.error("The nicknames could not be read; commands only know real names this session.");
        }
    }

    /** Writes whatever changed. Off the server's threads. */
    public void flush() {
        synchronized (flushing) {
            if (changed.isEmpty() || !database.isUsable()) {
                return;
            }
            Set<UUID> writing = Marks.drainInto(changed, inFlight);
            try {
                writeAndAnnounce(writing);
            } finally {
                inFlight.removeAll(writing);
            }
        }
    }

    /** Players being written right now: a refresh in that moment keeps them, as it keeps unwritten ones. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    private void writeAndAnnounce(Set<UUID> writing) {
        boolean written = database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO nickname (player, plain, typed) VALUES (?, ?, ?)
                    ON CONFLICT(player) DO UPDATE SET plain = excluded.plain, typed = excluded.typed""");
                 PreparedStatement remove =
                         connection.prepareStatement("DELETE FROM nickname WHERE player = ?")) {
                for (UUID player : writing) {
                    Entry entry = entries.get(player);
                    if (entry == null) {
                        remove.setString(1, player.toString());
                        remove.executeUpdate();
                        continue;
                    }
                    upsert.setString(1, player.toString());
                    upsert.setString(2, entry.plain());
                    upsert.setString(3, entry.typed());
                    upsert.executeUpdate();
                }
            }
        });
        if (!written) {
            Marks.restore(changed, writing);
            return;
        }
        de.raindancer.core.platform.network.NetworkBus network = bus;
        if (network != null) {
            inFlight.removeAll(writing);
            writing.forEach(this::refresh);
            writing.forEach(player -> network.publish(TOPIC, player.toString()));
        }
    }

    // ------------------------------------------------------------------ the network

    /** The topic other servers are told on, keyed by the player. */
    public static final String TOPIC = "nickname";

    private volatile de.raindancer.core.platform.network.NetworkBus bus;

    /** Joins the network: a nickname taken on one server is found by name on all of them. */
    public void connect(de.raindancer.core.platform.network.NetworkBus network) {
        this.bus = network;
        if (network != null) {
            network.subscribe(TOPIC, event -> {
                try {
                    refresh(UUID.fromString(event.key()));
                } catch (IllegalArgumentException notAPlayer) {
                    log.warn("A nickname event named no player: '{}'", event.key());
                }
            });
            network.onResync(this::refreshAll);
        }
    }

    /**
     * Reads one player's nickname from the database again; an unwritten change of this server's wins,
     * and an unreadable database leaves what is known. Off the server's threads.
     */
    public void refresh(UUID player) {
        if (player == null || !database.isUsable()) {
            return;
        }
        Optional<Optional<Entry>> stored = database.read(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT plain, typed FROM nickname WHERE player = ?")) {
                statement.setString(1, player.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next()
                            ? Optional.of(new Entry(rows.getString("plain"), rows.getString("typed")))
                            : Optional.<Entry>empty();
                }
            }
        });
        if (stored.isEmpty()) {
            return;
        }
        takeStored(player, stored.get().orElse(null));
    }

    /** Reads every nickname again — for when this server may have missed what others announced. */
    public void refreshAll() {
        if (!database.isUsable()) {
            return;
        }
        Optional<Map<UUID, Entry>> stored = database.read(connection -> {
            Map<UUID, Entry> found = new java.util.HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT player, plain, typed FROM nickname");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    try {
                        found.put(UUID.fromString(rows.getString("player")),
                                new Entry(rows.getString("plain"), rows.getString("typed")));
                    } catch (RuntimeException broken) {
                        log.warn("A nickname row could not be read and was skipped ({})", broken.getMessage());
                    }
                }
            }
            return found;
        });
        if (stored.isEmpty()) {
            return;
        }
        Set<UUID> everybody = new java.util.HashSet<>(entries.keySet());
        everybody.addAll(stored.get().keySet());
        everybody.forEach(player -> takeStored(player, stored.get().get(player)));
    }

    private void takeStored(UUID player, Entry found) {
        entries.compute(player, (key, current) -> changed.contains(player) || inFlight.contains(player)
                ? current : found);
    }
}
