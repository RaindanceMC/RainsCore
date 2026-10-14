package de.raindancer.core.ui.changelog;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * What changed on the server, told to every returning player once, the first time they join after it went out.
 *
 * <p>Entries are written by hand in {@code changelog.yml} and stay drafts — seen by nobody but staff
 * previewing them — until somebody publishes one. Publishing is the release: the time goes into the file and
 * from then on everybody who comes back is shown it. Per player only one number is kept, when they were last
 * told, so a new entry needs nothing written per player. Thread-safe.
 */
public final class Changelog {

    public enum Publish { PUBLISHED, ALREADY, UNKNOWN, NOT_SAVED }

    /** Somebody back after months is shown the newest few; /changelog has the rest. */
    public static final int MOST_ON_JOIN = 3;

    private final YamlStore entriesFile;
    private final YamlStore seenFile;
    private final LongSupplier clock;
    private volatile List<ChangelogEntry> entries = List.of();
    private volatile List<String> problems = List.of();
    private final Map<UUID, Long> seenUpTo = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public Changelog(Path entriesFile, Path seenFile, LongSupplier clock) {
        // One changelog for the network, and what each player has seen of it wherever they read it.
        this.entriesFile = YamlStore.network(entriesFile);
        this.seenFile = YamlStore.network(seenFile);
        this.clock = clock;
    }

    /** Reads both files: the entries, which may have been edited by hand, and who was told when. */
    public synchronized void reload() {
        readEntries();
        YamlConfiguration seen = seenFile.read();
        ConfigurationSection players = seen.getConfigurationSection("players");
        seenUpTo.clear();
        if (players != null) {
            for (String key : players.getKeys(false)) {
                try {
                    seenUpTo.put(UUID.fromString(key), players.getLong(key));
                } catch (IllegalArgumentException notAnId) {
                    // Hand-edited; skipped.
                }
            }
        }
        dirty = false;
    }

    private void readEntries() {
        YamlConfiguration yaml = entriesFile.read();
        List<String> found = new ArrayList<>(entriesFile.problems());
        List<ChangelogEntry> read = new ArrayList<>();
        ConfigurationSection section = yaml.getConfigurationSection("entries");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                String title = section.getString(id + ".title", "");
                List<String> lines = section.getStringList(id + ".lines");
                if (title.isBlank() || lines.isEmpty()) {
                    found.add("changelog.yml: entry '" + id + "' needs a title and at least one line; it is not shown.");
                    continue;
                }
                read.add(new ChangelogEntry(id, title, lines, section.getLong(id + ".published", 0L)));
            }
        }
        entries = List.copyOf(read);
        problems = List.copyOf(found);
    }

    public List<String> problems() {
        return problems;
    }

    public Optional<ChangelogEntry> entry(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    /** In the order they are in the file. */
    public List<ChangelogEntry> drafts() {
        return entries.stream().filter(entry -> !entry.isPublished()).toList();
    }

    /** Newest first. */
    public List<ChangelogEntry> published() {
        return entries.stream().filter(ChangelogEntry::isPublished)
                .sorted(Comparator.comparingLong(ChangelogEntry::publishedAt).reversed()).toList();
    }

    /**
     * Sends a draft out: from now on every player who comes back is told. Written to the file at once,
     * because a publish lost to a crash would be told again to everybody after it.
     */
    public synchronized Publish publish(String id) {
        ChangelogEntry entry = entry(id).orElse(null);
        if (entry == null) {
            return Publish.UNKNOWN;
        }
        if (entry.isPublished()) {
            return Publish.ALREADY;
        }
        long now = clock.getAsLong();
        boolean saved = entriesFile.update(yaml -> yaml.set("entries." + id + ".published", now));
        readEntries();
        if (!saved) {
            // The file is broken (problems() says how); kept in memory so it still goes out this run.
            List<ChangelogEntry> patched = new ArrayList<>(entries);
            patched.replaceAll(each -> each.id().equals(id)
                    ? new ChangelogEntry(id, each.title(), each.lines(), now) : each);
            entries = List.copyOf(patched);
            return Publish.NOT_SAVED;
        }
        return Publish.PUBLISHED;
    }

    /**
     * What to tell somebody joining now, newest first, and they count as told.
     *
     * @param returning whether they played here before; a newcomer is not handed the server's history
     */
    public synchronized List<ChangelogEntry> joined(UUID player, boolean returning) {
        long now = clock.getAsLong();
        Long last = seenUpTo.get(player);
        long since = last != null ? last : returning ? 0L : now;
        List<ChangelogEntry> news = published().stream()
                .filter(entry -> entry.publishedAt() > since)
                .limit(MOST_ON_JOIN)
                .toList();
        seenUpTo.put(player, now);
        dirty = true;
        return news;
    }

    /** They were told just now, while online. */
    public void seen(UUID player) {
        seenUpTo.put(player, clock.getAsLong());
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public boolean save() {
        dirty = false;
        Map<UUID, Long> snapshot = new HashMap<>(seenUpTo);
        boolean saved = seenFile.write(yaml -> snapshot.forEach((id, at) -> yaml.set("players." + id, at)));
        if (!saved) {
            dirty = true;
        }
        return saved;
    }
}
