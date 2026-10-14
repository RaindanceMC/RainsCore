package de.raindancer.core.social.presence;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.network.NetworkBus;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How long everybody has played on the network, and how much of it they were not away — for profiles, staff
 * screens, and anything that weighs "lately" by play rather than by the calendar.
 *
 * <p>Counted a minute at a time by whichever server a player is on, seeded once from the game's own statistic
 * so players from before this existed do not start at zero. Written as additions, never as totals: around a
 * server switch two servers may both hold unwritten minutes for one player, and "add 2" from each lands where
 * "it is 92" from each would leave one of them lost. Thread-safe; written by {@link #save} from wherever the
 * caller likes.
 */
public final class Playtime {

    private static final LogChannel log = Log.of("presence");

    /** The topic other servers are told on, keyed by the player. */
    public static final String TOPIC = "playtime";

    private record Entry(long minutes, long active) {
        Entry plus(Entry other) {
            return new Entry(minutes + other.minutes, active + other.active);
        }
    }

    /** What this server counted and has not written: added minutes, or a seed that only counts if nobody has one. */
    private record Pending(Entry added, Entry seed) {
        Pending then(Pending later) {
            Entry addedTogether = added == null ? later.added : later.added == null ? added : added.plus(later.added);
            return new Pending(addedTogether, seed != null ? seed : later.seed);
        }
    }

    private final Database database;
    private final Map<UUID, Entry> stored = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
    private volatile NetworkBus bus;

    public Playtime(Database database) {
        this.database = database;
    }

    private Object lockOf(UUID player) {
        return locks.computeIfAbsent(player, key -> new Object());
    }

    /** Everybody's playtime, from the database. Off the server's threads. */
    public void load() {
        if (database == null || !database.isUsable()) {
            log.warn("Playtime cannot be read: the database is not available. Minutes are counted and written once it is.");
            return;
        }
        database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT player, minutes, active FROM playtime");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    try {
                        stored.put(UUID.fromString(rows.getString(1)), new Entry(rows.getLong(2), rows.getLong(3)));
                    } catch (IllegalArgumentException notAnId) {
                        log.warn("A playtime row for '{}' was skipped: that is not a player id.", rows.getString(1));
                    }
                }
            }
            return true;
        });
    }

    /** Joins the network: minutes counted on another server show here once they are written. */
    public void connect(NetworkBus network) {
        this.bus = network;
        if (network != null) {
            network.subscribe(TOPIC, event -> {
                try {
                    refresh(UUID.fromString(event.key()));
                } catch (IllegalArgumentException notAPlayer) {
                    log.warn("A playtime event named no player: '{}'", event.key());
                }
            });
            network.onResync(this::load);
        }
    }

    /** Reads one player's minutes from the database again. Off the server's threads. */
    public void refresh(UUID player) {
        if (player == null || database == null || !database.isUsable()) {
            return;
        }
        Optional<Optional<Entry>> read = database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT minutes, active FROM playtime WHERE player = ?")) {
                statement.setString(1, player.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(new Entry(rows.getLong(1), rows.getLong(2))) : Optional.<Entry>empty();
                }
            }
        });
        if (read.isEmpty()) {
            return;
        }
        synchronized (lockOf(player)) {
            if (read.get().isPresent()) {
                stored.put(player, read.get().get());
            } else {
                stored.remove(player);
            }
        }
    }

    /** Starts somebody from the game's own statistic, the first time anybody on the network counts them. */
    public void seed(UUID player, long ticksPlayed) {
        long minutes = Math.max(0, ticksPlayed) / 20 / 60;
        synchronized (lockOf(player)) {
            if (isKnown(player)) {
                return;
            }
            pending.merge(player, new Pending(null, new Entry(minutes, minutes)), Pending::then);
        }
    }

    /** One more minute online; {@code away} minutes count as played but not as active. */
    public void minute(UUID player, boolean away) {
        synchronized (lockOf(player)) {
            pending.merge(player, new Pending(new Entry(1, away ? 0 : 1), null), Pending::then);
        }
    }

    public boolean isKnown(UUID player) {
        return stored.containsKey(player) || pending.containsKey(player);
    }

    public long minutes(UUID player) {
        return current(player).minutes();
    }

    public long activeMinutes(UUID player) {
        return current(player).active();
    }

    public long awayMinutes(UUID player) {
        return Math.max(0, minutes(player) - activeMinutes(player));
    }

    public Duration playedAway(UUID player) {
        return Duration.ofMinutes(awayMinutes(player));
    }

    public Duration played(UUID player) {
        return Duration.ofMinutes(minutes(player));
    }

    public Duration playedActively(UUID player) {
        return Duration.ofMinutes(activeMinutes(player));
    }

    /** What the database holds plus what this server has not written yet. */
    private Entry current(UUID player) {
        if (player == null) {
            return new Entry(0, 0);
        }
        synchronized (lockOf(player)) {
            Entry base = stored.get(player);
            Pending mine = pending.get(player);
            if (mine != null && base == null && mine.seed() != null) {
                base = mine.seed();
            }
            Entry total = base == null ? new Entry(0, 0) : base;
            return mine == null || mine.added() == null ? total : total.plus(mine.added());
        }
    }

    public boolean isDirty() {
        return !pending.isEmpty();
    }

    /**
     * Writes what was counted since the last save. Off the server's threads.
     *
     * @return whether everything is written; what was not stays for the next save
     */
    public synchronized boolean save() {
        if (pending.isEmpty()) {
            return true;
        }
        if (database == null || !database.isUsable()) {
            return false;
        }
        Map<UUID, Pending> writing = new HashMap<>();
        for (UUID player : List.copyOf(pending.keySet())) {
            synchronized (lockOf(player)) {
                Pending taken = pending.remove(player);
                if (taken != null) {
                    writing.put(player, taken);
                    // Folded into the stored copy now, so the minutes never vanish from view while they are written.
                    Entry base = stored.get(player);
                    if (base == null && taken.seed() != null) {
                        base = taken.seed();
                    }
                    Entry total = base == null ? new Entry(0, 0) : base;
                    stored.put(player, taken.added() == null ? total : total.plus(taken.added()));
                }
            }
        }
        boolean written = database.write(connection -> {
            try (PreparedStatement seed = connection.prepareStatement(
                         "INSERT INTO playtime (player, minutes, active) VALUES (?, ?, ?) ON CONFLICT(player) DO NOTHING");
                 PreparedStatement add = connection.prepareStatement("""
                         INSERT INTO playtime (player, minutes, active) VALUES (?, ?, ?)
                         ON CONFLICT(player) DO UPDATE SET minutes = playtime.minutes + excluded.minutes,
                             active = playtime.active + excluded.active""")) {
                for (Map.Entry<UUID, Pending> each : writing.entrySet()) {
                    Pending counted = each.getValue();
                    if (counted.seed() != null) {
                        seed.setString(1, each.getKey().toString());
                        seed.setLong(2, counted.seed().minutes());
                        seed.setLong(3, counted.seed().active());
                        seed.executeUpdate();
                    }
                    if (counted.added() != null) {
                        add.setString(1, each.getKey().toString());
                        add.setLong(2, counted.added().minutes());
                        add.setLong(3, counted.added().active());
                        add.executeUpdate();
                    }
                }
            }
        });
        if (!written) {
            writing.forEach((player, taken) -> {
                synchronized (lockOf(player)) {
                    pending.merge(player, taken, (later, earlier) -> earlier.then(later));
                }
            });
            return false;
        }
        // Read back: a seed another server got in first, and minutes it added meanwhile, are now in view.
        writing.keySet().forEach(this::refresh);
        NetworkBus network = bus;
        if (network != null) {
            writing.keySet().forEach(player -> network.publish(TOPIC, player.toString()));
        }
        return true;
    }

    /**
     * Brings in a 1.x {@code playtime.yml}: its minutes are added, since each server counted only the time
     * played on it. The file is kept renamed {@code *.imported-2.0.0}, never deleted. Off the server's threads.
     */
    public void importFrom(Path file) {
        if (file == null || !Files.isRegularFile(file) || database == null || !database.isUsable()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file));
        } catch (IOException | InvalidConfigurationException unreadable) {
            log.error("{} could not be read, so its playtime was not brought into the database: {}", file,
                    unreadable.getMessage());
            return;
        }
        ConfigurationSection players = yaml.getConfigurationSection("players");
        Map<UUID, Entry> found = new HashMap<>();
        if (players != null) {
            for (String key : players.getKeys(false)) {
                try {
                    found.put(UUID.fromString(key), new Entry(players.getLong(key + ".minutes"), players.getLong(key + ".active")));
                } catch (IllegalArgumentException notAnId) {
                    log.warn("{}: '{}' is not a player id; skipped.", file.getFileName(), key);
                }
            }
        }
        boolean written = database.write(connection -> {
            try (PreparedStatement add = connection.prepareStatement("""
                    INSERT INTO playtime (player, minutes, active) VALUES (?, ?, ?)
                    ON CONFLICT(player) DO UPDATE SET minutes = playtime.minutes + excluded.minutes,
                        active = playtime.active + excluded.active""")) {
                for (Map.Entry<UUID, Entry> each : found.entrySet()) {
                    add.setString(1, each.getKey().toString());
                    add.setLong(2, each.getValue().minutes());
                    add.setLong(3, each.getValue().active());
                    add.executeUpdate();
                }
            }
        });
        if (!written) {
            log.error("{} could not be brought into the database; it stays a file and is tried again next start.", file);
            return;
        }
        Path kept = file.resolveSibling(file.getFileName() + ".imported-2.0.0");
        try {
            Files.move(file, kept);
            log.info("{} players' playtime from {} is in the database now; the file is kept as {}.", found.size(),
                    file.getFileName(), kept.getFileName());
        } catch (IOException failure) {
            // Left in place it would be added a second time next start — worth shouting about.
            log.error("{} was brought in but could not be renamed ({}). Rename or move it before the next start, or "
                    + "its minutes are counted twice.", file, failure.getMessage());
        }
        load();
    }
}
