package de.raindancer.core.content.achievement;

import de.raindancer.core.platform.util.PluginCode;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.YamlStore;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.Objects;

/**
 * Custom achievements: what a player has done, and what they are working towards.
 *
 * <h2>The one thing that has to be right</h2>
 * An achievement is earned <em>once</em>. A player who claims their second plot has not earned
 * "your first claim" again, and a listener that fires twice must not announce it twice — which is
 * the bug every hand-rolled version of this has, because the check and the write are two steps and
 * something always slips between them. Here they are one atomic step, so eight threads awarding at
 * the same moment award it once and announce it once.
 *
 * <h2>How a plugin uses it</h2>
 * <pre>
 * // At startup, shipping a default the owner may then edit:
 * achievements.defineIfAbsent(Achievement.builder("claims", "first-claim")
 *         .title("&lt;gold&gt;Landowner")
 *         .description("Claim your first plot")
 *         .icon(Material.GRASS_BLOCK)
 *         .points(10)
 *         .build());
 *
 * // When it happens — safe to call every time, it only lands once:
 * achievements.award(player, "claims:first-claim");
 *
 * // Or, for one they work towards:
 * achievements.progress(player, "ghasts:every-stop", 1);
 * </pre>
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. Awards are held in a {@link ConcurrentHashMap} per player and the award
 * itself is a {@code putIfAbsent}, which is what makes "only once" true rather than hoped for.
 */
public final class Achievements {

    private static final LogChannel log = Log.of("achievements");

    private final Path file;
    private final YamlStore store;
    private final LongSupplier clock;
    private final Map<String, Achievement> defined = new ConcurrentHashMap<>();
    /** Who has earned what, and when. */
    private final Map<UUID, Map<String, Instant>> earned = new ConcurrentHashMap<>();
    /** How far along somebody is with one they are working towards. */
    private final Map<UUID, Map<String, Integer>> progress = new ConcurrentHashMap<>();
    private final List<BiConsumer<UUID, Achievement>> listeners = new CopyOnWriteArrayList<>();
    private final Database database;
    /** Set when a definition changed and the file needs rewriting. */
    private final AtomicBoolean dirty = new AtomicBoolean();
    /**
     * What this server changed for each player and has not written yet — as operations, not results.
     *
     * <p>On a network a player's progress is changed by whichever server they are on, and two servers
     * may both hold unwritten progress for them for a moment around a server switch. Writing results
     * ("sofar is 12") would let the later write undo the earlier one; writing operations ("add 3",
     * "earned at t", "revoked") lets both land. The database applies them; a {@link #refresh} reads
     * the result and lays whatever is still pending here on top.
     */
    private final Map<UUID, PlayerChanges> pending = new ConcurrentHashMap<>();

    /** One lock per player: their cached rows and their pending operations change together. */
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    private Object lockOf(UUID player) {
        return locks.computeIfAbsent(player, key -> new Object());
    }

    private PlayerChanges changesOf(UUID player) {
        return pending.computeIfAbsent(player, key -> new PlayerChanges());
    }

    /**
     * @param clock milliseconds; injected so "when was this earned" can be tested
     * @param file     where the achievement <em>definitions</em> live: what they are called, what they
     *                 are worth, what they look like. Written by whoever runs the server and read at
     *                 startup, so a file with comments in it is the right home for them —
     *                 {@link #defineIfAbsent} exists precisely so a plugin's defaults do not undo the
     *                 owner's edits
     * @param database where <em>who earned what</em> lives. Written while players are on, never
     *                 edited by hand, and the half that a kill mid-save would otherwise cost somebody
     */
    public Achievements(Path file, Database database, LongSupplier clock) {
        this.database = database;
        this.file = file;
        // The network's one set of definitions; read again when another server changes them.
        this.store = YamlStore.network(file);
        this.store.onChange(this::reloadDefinitions);
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------- defining

    public void define(Achievement achievement) {
        if (achievement != null) {
            defined.put(achievement.key(), achievement);
            dirty.set(true);
        }
    }

    /**
     * Defines it only if nobody has — how a plugin ships a default without undoing the owner's edits
     * on every restart.
     */
    public boolean defineIfAbsent(Achievement achievement) {
        if (achievement == null) {
            return false;
        }
        boolean added = defined.putIfAbsent(achievement.key(), achievement) == null;
        if (added) {
            dirty.set(true);
        }
        return added;
    }

    public Optional<Achievement> byKey(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(defined.get(normalise(key)));
    }

    public List<Achievement> all() {
        return List.copyOf(defined.values());
    }

    public List<Achievement> ofPlugin(String plugin) {
        if (plugin == null) {
            return List.of();
        }
        String wanted = plugin.trim().toLowerCase(Locale.ROOT);
        return defined.values().stream().filter(each -> each.plugin().equals(wanted)).toList();
    }

    /**
     * What this player should see in a list: everything, minus the hidden ones they have not earned.
     *
     * <p>Hidden ones appear the moment they are earned, which is the point of them — the surprise
     * is in the finding, not in never being told it existed.
     */
    public List<Achievement> visibleTo(UUID player) {
        return defined.values().stream()
                .filter(each -> !each.hidden() || hasEarned(player, each.key()))
                .toList();
    }

    // ---------------------------------------------------------------------------- earning

    /**
     * Records that a player earned this. Safe to call every time the thing happens.
     *
     * @return whether this was the first time; false means they already had it
     */
    public boolean award(UUID player, String key) {
        if (player == null || key == null) {
            return false;
        }
        Achievement achievement = defined.get(normalise(key));
        if (achievement == null) {
            // Deliberately not invented: an achievement nobody defined has no title, no icon and no
            // points, so awarding it would put a blank line in somebody's list for ever.
            log.warn("{} was awarded '{}', which nothing defines.", player, key);
            return false;
        }
        // One atomic step. Checking and then writing is where "announced twice" comes from.
        Instant at = Instant.ofEpochMilli(clock.getAsLong());
        synchronized (lockOf(player)) {
            Instant already = earnedMap(player).putIfAbsent(achievement.key(), at);
            if (already != null) {
                return false;
            }
            changesOf(player).earn(achievement.key(), at);
        }
        announce(player, achievement);
        return true;
    }

    /** Takes one back, for a mistake. */
    public boolean revoke(UUID player, String key) {
        if (player == null || key == null) {
            return false;
        }
        synchronized (lockOf(player)) {
            Map<String, Instant> theirs = earned.get(player);
            if (theirs == null || theirs.remove(normalise(key)) == null) {
                return false;
            }
            changesOf(player).revoke(normalise(key));
        }
        return true;
    }

    public boolean hasEarned(UUID player, String key) {
        return player != null && key != null && earnedBy(player).containsKey(normalise(key));
    }

    /** When they earned it, if they did. */
    public Optional<Instant> earnedAt(UUID player, String key) {
        if (player == null || key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(earnedBy(player).get(normalise(key)));
    }

    /**
     * Everything this player has earned. Asking about somebody who has earned nothing creates nothing:
     * a leaderboard or a profile page looking players up must not leave an entry for each of them.
     */
    public Map<String, Instant> earnedBy(UUID player) {
        if (player == null) {
            return Map.of();
        }
        Map<String, Instant> theirs = earned.get(player);
        return theirs == null ? Map.of() : theirs;
    }

    private Map<String, Instant> earnedMap(UUID player) {
        return earned.computeIfAbsent(player, key -> new ConcurrentHashMap<>());
    }

    /** What this player's achievements are worth, for a leaderboard. */
    public int pointsOf(UUID player) {
        return earnedBy(player).keySet().stream()
                .map(defined::get)
                .filter(Objects::nonNull)
                .mapToInt(Achievement::points)
                .sum();
    }

    // ---------------------------------------------------------------------------- progress

    /**
     * Adds to how far along a player is, and awards it when they reach the goal.
     *
     * <p>An achievement with no goal is completed by any progress at all, so a plugin does not have
     * to know which kind it is dealing with.
     */
    public void progress(UUID player, String key, int by) {
        if (player == null || key == null) {
            return;
        }
        String normalised = normalise(key);
        int added = Math.max(0, by);
        int updated;
        synchronized (lockOf(player)) {
            updated = progressOf(player).merge(normalised, added, Integer::sum);
            changesOf(player).add(normalised, added);
        }
        checkGoal(player, normalised, updated);
    }

    /** Sets it outright — for a count that is recomputed rather than added to. */
    public void setProgress(UUID player, String key, int to) {
        if (player == null || key == null) {
            return;
        }
        String normalised = normalise(key);
        synchronized (lockOf(player)) {
            progressOf(player).put(normalised, Math.max(0, to));
            changesOf(player).set(normalised, Math.max(0, to));
        }
        checkGoal(player, normalised, Math.max(0, to));
    }

    /** How far along they are. */
    public int progressOf(UUID player, String key) {
        if (player == null || key == null) {
            return 0;
        }
        return progressOf(player).getOrDefault(normalise(key), 0);
    }

    private Map<String, Integer> progressOf(UUID player) {
        return progress.computeIfAbsent(player, key -> new ConcurrentHashMap<>());
    }

    private void checkGoal(UUID player, String key, int now) {
        Achievement achievement = defined.get(key);
        if (achievement == null) {
            return;
        }
        int goal = achievement.goal().orElse(1);
        if (now >= goal) {
            // award() is the one that guarantees once, so going past the goal is harmless.
            award(player, key);
        }
    }

    // ---------------------------------------------------------------------------- announcing

    /**
     * Called whenever somebody earns one — for whatever announces it.
     *
     * <p>Deliberately not announced from here: what a server does about an achievement is its own
     * business, and a title, a sound, a broadcast and a firework are all reasonable and none of them
     * belongs in a store.
     */
    /**
     * Drops whatever {@code loader}'s code registered here — for a plugin disabled without taking its
     * own back. See {@link PluginCode}.
     *
     * @return how many were dropped
     */
    public int forgetFrom(ClassLoader loader) {
        int before = listeners.size();
        listeners.removeIf(listener -> PluginCode.isFrom(listener, loader));
        return before - listeners.size();
    }

    public void onEarned(BiConsumer<UUID, Achievement> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void announce(UUID player, Achievement achievement) {
        for (BiConsumer<UUID, Achievement> listener : listeners) {
            try {
                listener.accept(player, achievement);
            } catch (RuntimeException broken) {
                // One plugin's celebration must not stop another's, nor undo the award itself.
                log.error(broken, "An achievement listener threw for {}.", achievement.key());
            }
        }
    }

    private static String normalise(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------------------- the file

    /** Whether either half is waiting to be written. */
    public boolean isDirty() {
        return dirty.get() || !pending.isEmpty();
    }

    public void load() {
        defined.clear();
        unread.clear();
        earned.clear();
        progress.clear();
        // Who earned what is loaded whatever happens to the definitions file. It lives in the database,
        // and a restore or a deploy without achievements.yml used to leave it unloaded — after which
        // the next flush for anybody who earned something wrote their rows back without the old ones.
        if (!store.exists()) {
            dirty.set(false);
            loadPlayers();
            return;
        }
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            log.error("Could not read {} ({}); the achievements defined in it are unknown this session, "
                    + "but what everybody earned is kept.", file, String.join("; ", store.problems()));
            loadPlayers();
            return;
        }
        readDefinitions(yaml.getConfigurationSection("achievements"));
        dirty.set(false);
        loadPlayers();
    }

    /**
     * Reads who has earned what, out of the database.
     *
     * <p>Separate from the definitions on purpose: what an achievement <em>is</em> comes from a file
     * somebody wrote, and who <em>earned</em> it is something the server recorded. The two have
     * different owners and different failure modes, so they have different homes.
     */
    /** The definitions again, after another server changed them — players' achievements are left as they are. */
    private void reloadDefinitions() {
        flushDefinitions();
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            return;
        }
        defined.clear();
        unread.clear();
        readDefinitions(yaml.getConfigurationSection("achievements"));
        dirty.set(false);
    }

    private void loadPlayers() {
        earned.clear();
        progress.clear();
        pending.clear();
        if (!database.isUsable()) {
            log.error("The achievement tables are not available; nobody's achievements are known "
                    + "this session.");
            return;
        }
        boolean read = database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT player, achievement, earned_at FROM achievement_earned");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID player = playerOf(rows.getString("player"));
                    if (player != null) {
                        earnedMap(player).put(rows.getString("achievement"),
                                Instant.ofEpochMilli(rows.getLong("earned_at")));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT player, achievement, sofar FROM achievement_progress");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID player = playerOf(rows.getString("player"));
                    if (player != null) {
                        progressOf(player).put(rows.getString("achievement"), rows.getInt("sofar"));
                    }
                }
            }
            return true;
        }).orElse(false);
        if (!read) {
            log.error("The earned achievements could not be read; nobody's are known this session.");
        }
    }

    /** A player id, or null when the row holds something that is not one. */
    private static UUID playerOf(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException notAUuid) {
            // One unreadable row is one player's achievement, not everybody's.
            log.warn("An achievement row for '{}' was skipped: that is not a player id.", value);
            return null;
        }
    }

    private void readDefinitions(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            int colon = key.indexOf(':');
            if (entry == null || colon <= 0) {
                if (entry != null) {
                    unread.put(key, entry);
                }
                continue;
            }
            try {
                Achievement.Builder built = Achievement.builder(YamlStore.fromPathPart(key.substring(0, colon)),
                                YamlStore.fromPathPart(key.substring(colon + 1)))
                        .title(entry.getString("title"))
                        .description(entry.getString("description"))
                        .points(entry.getInt("points"))
                        .hidden(entry.getBoolean("hidden"));
                String icon = entry.getString("icon");
                if (icon != null && !icon.isBlank()) {
                    built.icon(Material.matchMaterial(icon));
                }
                if (entry.contains("goal")) {
                    built.goal(entry.getInt("goal"));
                }
                Achievement achievement = built.build();
                defined.put(achievement.key(), achievement);
            } catch (RuntimeException broken) {
                log.warn("{}: achievement '{}' was skipped ({})",
                        file.getFileName(), key, broken.getMessage());
                unread.put(key, entry);
            }
        }
    }


    /** Writes, if anything changed. Via a temporary file, so a kill mid-write cannot truncate it. */
    /**
     * Writes both halves: the definitions to their file, and who earned what to the database.
     *
     * <p>Two halves with two conditions, so a server where nobody has changed a definition does not
     * rewrite the definitions file every two minutes to record that somebody earned something.
     *
     * <p>Must be called off the server's threads.
     */
    public void flush() {
        synchronized (flushing) {
            writeChanges();
        }
    }

    /** Definitions the file had that this server could not read, kept to be written back untouched. */
    private final Map<String, ConfigurationSection> unread = new ConcurrentHashMap<>();

    /**
     * One flush at a time. The saving timer can still be mid-flush when shutdown flushes by hand, and
     * two at once either land out of order or answer "written" while the other still holds the rows.
     */
    private final Object flushing = new Object();

    private void writeChanges() {
        flushDefinitions();
        flushPlayers();
    }

    private void flushDefinitions() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        List<Achievement> snapshot = List.copyOf(defined.values());
        boolean written = store.write(yaml -> {
            for (Achievement achievement : snapshot) {
                String path = "achievements." + YamlStore.asPathPart(achievement.key()) + ".";
                yaml.set(path + "title", achievement.title());
                if (!achievement.description().isEmpty()) {
                    yaml.set(path + "description", achievement.description());
                }
                yaml.set(path + "icon", achievement.icon().name());
                if (achievement.points() > 0) {
                    yaml.set(path + "points", achievement.points());
                }
                achievement.goal().ifPresent(goal -> yaml.set(path + "goal", goal));
                if (achievement.hidden()) {
                    yaml.set(path + "hidden", true);
                }
            }
            // Written back as found: skipped on this server is not deleted from the file.
            unread.forEach((key, kept) -> {
                if (!defined.containsKey(YamlStore.fromPathPart(key).toLowerCase(Locale.ROOT))) {
                    yaml.set("achievements." + key, kept);
                }
            });
        });
        if (!written) {
            dirty.set(true);
        }
    }

    /**
     * Writes the players whose achievements changed.
     *
     * <p>Everything a player has is rewritten rather than the one thing that changed: a revoke has to
     * take a row away, and working out which rows went is more code than replacing the handful a
     * player has. All inside one transaction, so nobody is ever seen mid-rewrite.
     */
    private void flushPlayers() {
        if (pending.isEmpty() || !database.isUsable()) {
            return;
        }
        // Taken off, not copied: an operation arriving while the write runs lands in a fresh entry and
        // is written next time, rather than being cleared away with the ones written now.
        Map<UUID, PlayerChanges> writing = new java.util.HashMap<>();
        for (UUID player : List.copyOf(pending.keySet())) {
            synchronized (lockOf(player)) {
                PlayerChanges taken = pending.remove(player);
                if (taken != null && !taken.isEmpty()) {
                    writing.put(player, taken);
                }
            }
        }
        if (writing.isEmpty()) {
            return;
        }
        boolean written = database.write(connection -> {
            try (PreparedStatement earn = connection.prepareStatement(
                         "INSERT INTO achievement_earned (player, achievement, earned_at) VALUES (?, ?, ?) "
                                 + "ON CONFLICT(player, achievement) DO NOTHING");
                 PreparedStatement revoke = connection.prepareStatement(
                         "DELETE FROM achievement_earned WHERE player = ? AND achievement = ?");
                 PreparedStatement set = connection.prepareStatement(
                         "INSERT INTO achievement_progress (player, achievement, sofar) VALUES (?, ?, ?) "
                                 + "ON CONFLICT(player, achievement) DO UPDATE SET sofar = excluded.sofar");
                 PreparedStatement add = connection.prepareStatement(
                         "INSERT INTO achievement_progress (player, achievement, sofar) VALUES (?, ?, ?) "
                                 + "ON CONFLICT(player, achievement) DO UPDATE "
                                 + "SET sofar = achievement_progress.sofar + excluded.sofar")) {
                for (Map.Entry<UUID, PlayerChanges> each : writing.entrySet()) {
                    String player = each.getKey().toString();
                    PlayerChanges changes = each.getValue();
                    for (String key : changes.revoked) {
                        revoke.setString(1, player);
                        revoke.setString(2, key);
                        revoke.executeUpdate();
                    }
                    for (Map.Entry<String, Instant> got : changes.earned.entrySet()) {
                        earn.setString(1, player);
                        earn.setString(2, got.getKey());
                        earn.setLong(3, got.getValue().toEpochMilli());
                        earn.executeUpdate();
                    }
                    for (Map.Entry<String, PlayerChanges.Progress> towards : changes.progress.entrySet()) {
                        PreparedStatement statement = towards.getValue().absolute() ? set : add;
                        statement.setString(1, player);
                        statement.setString(2, towards.getKey());
                        statement.setInt(3, towards.getValue().value());
                        statement.executeUpdate();
                    }
                }
            }
        });
        if (!written) {
            // Put back in front of whatever arrived since, so the next flush applies them in order.
            writing.forEach((player, taken) -> {
                synchronized (lockOf(player)) {
                    pending.merge(player, taken, (newer, older) -> older.then(newer));
                }
            });
            return;
        }
        de.raindancer.core.platform.network.NetworkBus network = bus;
        if (network != null) {
            // Read back: a refresh another server caused while these were being written saw neither the
            // database with them nor them pending, and showed too little until now.
            writing.keySet().forEach(this::refresh);
            writing.keySet().forEach(player -> network.publish(TOPIC, player.toString()));
        }
    }

    // ---------------------------------------------------------------------------- the network

    /** The topic other servers are told on, keyed by the player. */
    public static final String TOPIC = "achievements";

    private volatile de.raindancer.core.platform.network.NetworkBus bus;

    /** Joins the network: what a player earns on one server is theirs on all of them. */
    public void connect(de.raindancer.core.platform.network.NetworkBus network) {
        this.bus = network;
        if (network != null) {
            network.subscribe(TOPIC, event -> {
                try {
                    refresh(UUID.fromString(event.key()));
                } catch (IllegalArgumentException notAPlayer) {
                    log.warn("An achievement event named no player: '{}'", event.key());
                }
            });
            network.onResync(this::refreshAll);
        }
    }

    /**
     * Reads one player's achievements and progress from the database again, with this server's unwritten
     * operations laid on top — nothing earned here is lost to what another server wrote, and nothing
     * written elsewhere is hidden by an old copy. Never announces: the server where it was earned did.
     * An unreadable database leaves what is known. Off the server's threads.
     */
    public void refresh(UUID player) {
        if (player == null || !database.isUsable()) {
            return;
        }
        Optional<Map.Entry<Map<String, Instant>, Map<String, Integer>>> stored = database.read(connection -> {
            Map<String, Instant> got = new java.util.HashMap<>();
            Map<String, Integer> towards = new java.util.HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT achievement, earned_at FROM achievement_earned WHERE player = ?")) {
                statement.setString(1, player.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        got.put(rows.getString("achievement"), Instant.ofEpochMilli(rows.getLong("earned_at")));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT achievement, sofar FROM achievement_progress WHERE player = ?")) {
                statement.setString(1, player.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        towards.put(rows.getString("achievement"), rows.getInt("sofar"));
                    }
                }
            }
            return Map.entry(got, towards);
        });
        if (stored.isEmpty()) {
            return;
        }
        takeStored(player, stored.get().getKey(), stored.get().getValue());
    }

    /** Reads every player's achievements again — for when this server may have missed what others announced. */
    public void refreshAll() {
        if (!database.isUsable()) {
            return;
        }
        Optional<Map.Entry<Map<UUID, Map<String, Instant>>, Map<UUID, Map<String, Integer>>>> stored =
                database.read(connection -> {
                    Map<UUID, Map<String, Instant>> got = new java.util.HashMap<>();
                    Map<UUID, Map<String, Integer>> towards = new java.util.HashMap<>();
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT player, achievement, earned_at FROM achievement_earned");
                         ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            UUID player = playerOf(rows.getString("player"));
                            if (player != null) {
                                got.computeIfAbsent(player, key -> new java.util.HashMap<>())
                                        .put(rows.getString("achievement"), Instant.ofEpochMilli(rows.getLong("earned_at")));
                            }
                        }
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT player, achievement, sofar FROM achievement_progress");
                         ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            UUID player = playerOf(rows.getString("player"));
                            if (player != null) {
                                towards.computeIfAbsent(player, key -> new java.util.HashMap<>())
                                        .put(rows.getString("achievement"), rows.getInt("sofar"));
                            }
                        }
                    }
                    return Map.entry(got, towards);
                });
        if (stored.isEmpty()) {
            return;
        }
        Set<UUID> everybody = new java.util.HashSet<>(earned.keySet());
        everybody.addAll(progress.keySet());
        everybody.addAll(stored.get().getKey().keySet());
        everybody.addAll(stored.get().getValue().keySet());
        everybody.forEach(player -> takeStored(player, stored.get().getKey().getOrDefault(player, Map.of()),
                stored.get().getValue().getOrDefault(player, Map.of())));
    }

    private void takeStored(UUID player, Map<String, Instant> storedEarned, Map<String, Integer> storedProgress) {
        synchronized (lockOf(player)) {
            Map<String, Instant> got = new ConcurrentHashMap<>(storedEarned);
            Map<String, Integer> towards = new ConcurrentHashMap<>(storedProgress);
            PlayerChanges mine = pending.get(player);
            if (mine != null) {
                mine.applyTo(got, towards);
            }
            if (got.isEmpty()) {
                earned.remove(player);
            } else {
                earned.put(player, got);
            }
            if (towards.isEmpty()) {
                progress.remove(player);
            } else {
                progress.put(player, towards);
            }
        }
    }

    /** What one player's achievements went through on this server since the last write. Guarded by their lock. */
    private static final class PlayerChanges {

        record Progress(boolean absolute, int value) {
        }

        final Map<String, Instant> earned = new java.util.HashMap<>();
        final Set<String> revoked = new java.util.HashSet<>();
        final Map<String, Progress> progress = new java.util.HashMap<>();

        void earn(String key, Instant at) {
            revoked.remove(key);
            earned.putIfAbsent(key, at);
        }

        void revoke(String key) {
            earned.remove(key);
            revoked.add(key);
        }

        void add(String key, int by) {
            progress.merge(key, new Progress(false, by),
                    (was, now) -> new Progress(was.absolute(), was.value() + now.value()));
        }

        void set(String key, int to) {
            progress.put(key, new Progress(true, to));
        }

        boolean isEmpty() {
            return earned.isEmpty() && revoked.isEmpty() && progress.isEmpty();
        }

        /** These, then {@code later} on top — for putting a failed write back in front of what came since. */
        PlayerChanges then(PlayerChanges later) {
            PlayerChanges combined = new PlayerChanges();
            combined.earned.putAll(earned);
            combined.revoked.addAll(revoked);
            combined.progress.putAll(progress);
            later.revoked.forEach(combined::revoke);
            later.earned.forEach(combined::earn);
            later.progress.forEach((key, step) -> {
                if (step.absolute()) {
                    combined.set(key, step.value());
                } else {
                    combined.add(key, step.value());
                }
            });
            return combined;
        }

        /** Lays these on top of what the database holds. */
        void applyTo(Map<String, Instant> got, Map<String, Integer> towards) {
            revoked.forEach(got::remove);
            earned.forEach(got::putIfAbsent);
            progress.forEach((key, step) -> towards.put(key,
                    step.absolute() ? step.value() : towards.getOrDefault(key, 0) + step.value()));
        }
    }
}
