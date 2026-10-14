package de.raindancer.core.content.loot;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.data.store.YamlStore;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Every loot table any plugin has defined.
 *
 * <p>The same shape as {@link de.raindancer.core.content.items.CustomItems}, and for the same reasons: a
 * plugin ships defaults with {@link #defineIfAbsent} so a restart does not undo the owner's edits,
 * one bad entry is one lost entry rather than an unreadable file, and the file is written through a
 * temporary so a kill mid-write cannot truncate it.
 */
public final class LootTables {

    private static final LogChannel log = Log.of("loot");

    private final Path file;
    private final YamlStore store;
    private final Map<String, LootTable> byKey = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final List<String> problems = new ArrayList<>();

    public LootTables(Path file) {
        this.file = file;
        // The network's one set of tables, read again when another server changes them.
        this.store = YamlStore.network(file);
        this.store.onChange(() -> {
            flush();
            load();
        });
    }

    // ---------------------------------------------------------------------------- defining

    public void define(LootTable table) {
        if (table != null) {
            byKey.put(table.key(), table);
            dirty.set(true);
        }
    }

    /** Defines it only if nobody has — how a plugin ships a default without undoing edits. */
    public boolean defineIfAbsent(LootTable table) {
        if (table == null) {
            return false;
        }
        boolean added = byKey.putIfAbsent(table.key(), table) == null;
        if (added) {
            dirty.set(true);
        }
        return added;
    }

    public boolean undefine(String key) {
        if (key == null || byKey.remove(key.trim().toLowerCase(Locale.ROOT)) == null) {
            return false;
        }
        dirty.set(true);
        return true;
    }

    // ---------------------------------------------------------------------------- asking

    public Optional<LootTable> byKey(String key) {
        return key == null ? Optional.empty()
                : Optional.ofNullable(byKey.get(key.trim().toLowerCase(Locale.ROOT)));
    }

    public List<LootTable> all() {
        return List.copyOf(byKey.values());
    }

    public List<String> keys() {
        return List.copyOf(byKey.keySet());
    }

    public List<LootTable> ofPlugin(String plugin) {
        if (plugin == null) {
            return List.of();
        }
        String wanted = plugin.trim().toLowerCase(Locale.ROOT);
        return byKey.values().stream().filter(table -> table.plugin().equals(wanted)).toList();
    }

    /** Every table of one tier — the ordinary chests, or the supply drops. */
    public List<LootTable> ofTier(int tier) {
        return byKey.values().stream().filter(table -> table.tier() == tier).toList();
    }

    /** Which tiers exist, in order. */
    public List<Integer> tiers() {
        return byKey.values().stream().map(LootTable::tier).distinct().sorted().toList();
    }

    public synchronized List<String> problems() {
        return List.copyOf(problems);
    }

    // ---------------------------------------------------------------------------- the file

    public boolean isDirty() {
        return dirty.get();
    }

    public void load() {
        synchronized (this) {
            problems.clear();
        }
        // Read first, replace after: an unreadable document leaves every table as it was.
        java.util.Optional<YamlConfiguration> fresh = store.readIfReadable();
        if (fresh.isEmpty()) {
            carry();
            return;
        }
        YamlConfiguration yaml = fresh.get();
        byKey.clear();
        unread.clear();
        unreadEntries.clear();
        ConfigurationSection section = yaml.getConfigurationSection("tables");
        if (section == null) {
            dirty.set(false);
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            int colon = key.indexOf(':');
            if (entry == null || colon <= 0) {
                note("'" + key + "' is not plugin:id");
                if (entry != null) {
                    unread.put(key, entry);
                }
                continue;
            }
            try {
                LootTable.Builder built = LootTable.builder(YamlStore.fromPathPart(key.substring(0, colon)),
                                YamlStore.fromPathPart(key.substring(colon + 1)))
                        .tier(entry.getInt("tier", 1))
                        .fillPercent(entry.getInt("fill-percent", 30));
                List<Map<?, ?>> kept = new ArrayList<>();
                for (Map<?, ?> raw : entry.getMapList("entries")) {
                    Optional<LootEntry> read = readEntry(key, raw);
                    if (read.isPresent()) {
                        built.entry(read.get());
                    } else {
                        kept.add(raw);
                    }
                }
                LootTable table = built.build();
                byKey.put(table.key(), table);
                if (!kept.isEmpty()) {
                    unreadEntries.put(table.key(), List.copyOf(kept));
                }
            } catch (RuntimeException broken) {
                note("'" + key + "' was skipped (" + broken.getMessage() + ")");
                unread.put(key, entry);
            }
        }
        dirty.set(false);
    }

    /** One entry, or empty when it names something this server does not have. */
    private Optional<LootEntry> readEntry(String tableKey, Map<?, ?> raw) {
        int weight = raw.get("weight") instanceof Number number ? number.intValue() : 1;
        int least = raw.get("min") instanceof Number number ? number.intValue() : 1;
        int most = raw.get("max") instanceof Number number ? number.intValue() : least;

        Object custom = raw.get("item");
        if (custom != null && !String.valueOf(custom).isBlank()) {
            return Optional.of(LootEntry.ofCustomItem(String.valueOf(custom), weight)
                    .amount(least, most));
        }
        String materialName = String.valueOf(raw.get("material"));
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            // One entry lost, not a table — a block renamed between versions must not take the
            // whole pool with it.
            note("'" + tableKey + "' names '" + materialName
                    + "', which this server has no block for; that entry is dropped");
            return Optional.empty();
        }
        return Optional.of(LootEntry.of(material, weight).amount(least, most));
    }

    /** Takes over what the file itself was wrong about; the store has already logged it. */
    private void carry() {
        List<String> fromFile = store.problems();
        synchronized (this) {
            problems.addAll(fromFile);
        }
    }

    private void note(String problem) {
        synchronized (this) {
            problems.add(problem);
        }
        log.warn("{}: {}", file.getFileName(), problem);
    }

    /** Writes, if anything changed. Via a temporary file, so a kill mid-write cannot truncate it. */
    public void flush() {
        synchronized (flushing) {
            writeChanges();
        }
    }

    /** Tables the file had that this server could not read, kept to be written back untouched. */
    private final Map<String, ConfigurationSection> unread = new ConcurrentHashMap<>();
    /** Single entries of a table this server could not read, kept to be written back with it. */
    private final Map<String, List<Map<?, ?>>> unreadEntries = new ConcurrentHashMap<>();

    /**
     * One flush at a time. The saving timer can still be mid-flush when shutdown flushes by hand, and
     * two at once either land out of order or answer "written" while the other still holds the rows.
     */
    private final Object flushing = new Object();

    private void writeChanges() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        List<LootTable> snapshot = List.copyOf(byKey.values());
        boolean written = store.write(yaml -> {
            for (LootTable table : snapshot) {
                String path = "tables." + YamlStore.asPathPart(table.key()) + ".";
                yaml.set(path + "tier", table.tier());
                yaml.set(path + "fill-percent", table.fillPercent());
                List<Object> entries = new ArrayList<>(table.entries().stream()
                        .map(LootTables::asMap)
                        .toList());
                // Entries this server could not read go back as they were: a block from a newer
                // version is not this server's to delete from somebody's loot pool.
                entries.addAll(unreadEntries.getOrDefault(table.key(), List.of()));
                yaml.set(path + "entries", entries);
            }
            // Written back as found: skipped on this server is not deleted from the file.
            unread.forEach((key, kept) -> {
                if (!byKey.containsKey(YamlStore.fromPathPart(key).toLowerCase(Locale.ROOT))) {
                    yaml.set("tables." + key, kept);
                }
            });
        });
        if (!written) {
            dirty.set(true);
        }
    }

    private static Map<String, Object> asMap(LootEntry entry) {
        Map<String, Object> written = new LinkedHashMap<>();
        if (entry.isCustom()) {
            written.put("item", entry.customKey());
        } else {
            written.put("material", entry.material().name());
        }
        written.put("weight", entry.weight());
        if (entry.minimum() != 1 || entry.maximum() != 1) {
            written.put("min", entry.minimum());
            written.put("max", entry.maximum());
        }
        return written;
    }
}
