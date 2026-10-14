package de.raindancer.core.content.items;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.data.store.YamlStore;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Every custom item any plugin has defined.
 *
 * <h2>What this is for</h2>
 * Every one of these plugins invents items: the claims module's selection stick, the record
 * seller's discs, the ghast lines' tickets. Each built its {@code ItemStack} by hand, stamped its
 * own key into the persistent data container, and had its own idea of how to tell one of its items
 * from a lookalike somebody crafted. Doing it once means an item defined by one plugin can be given,
 * recognised or listed by any other — and by a command and a menu — without either knowing about
 * the other.
 *
 * <h2>How a plugin uses it</h2>
 * <pre>
 * // At startup: ship a default, and leave whatever the owner has since changed alone.
 * items.defineIfAbsent(CustomItem.builder("claims", "selection-stick")
 *         .material(Material.STICK)
 *         .name("&lt;gold&gt;Claim Selection Stick")
 *         .glowing(true)
 *         .build());
 * </pre>
 * {@code defineIfAbsent} is the important half: a plugin that called {@code define} at every start
 * would undo the server owner's edits on every restart, which is the single most annoying thing a
 * plugin can do with a config file.
 */
public final class CustomItems {

    private static final LogChannel log = Log.of("items");

    private final Path file;
    private final YamlStore store;
    private final Map<String, CustomItem> byKey = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final List<String> problems = new ArrayList<>();

    public CustomItems(Path file) {
        this.file = file;
        // The network's one catalogue: an item defined on one server exists on all of them.
        this.store = YamlStore.network(file);
        // Changed elsewhere: what was changed here goes on top first, then everything is read again.
        this.store.onChange(() -> {
            flush();
            load();
        });
    }

    // ---------------------------------------------------------------------------- defining

    /** Defines an item, replacing any with the same key. */
    public void define(CustomItem item) {
        if (item == null) {
            return;
        }
        byKey.put(item.key(), item);
        dirty.set(true);
    }

    /**
     * Defines it only if nobody has yet — how a plugin ships a default.
     *
     * <h2>The one part of an existing definition this does overwrite</h2>
     * Which ability the item performs. Everything else here is the owner's: a name they changed, a lore line
     * they rewrote, a model number their resource pack needs. The ability is not — it is <em>code</em>, and
     * which one an item runs is a fact about the release rather than a preference.
     *
     * <p>Found by deploying. A server that had run an earlier build kept its {@code items.yml} from then, so
     * an item that gained an ability in a later release never got it: the definition was present, so this
     * declined, and the item stayed inert on exactly the servers that had been running longest. Nothing
     * said so — the boot log still counted the item.
     *
     * @return whether it was added; false means the owner's version was kept (its ability aside)
     */
    public boolean defineIfAbsent(CustomItem item) {
        if (item == null) {
            return false;
        }
        CustomItem existing = byKey.putIfAbsent(item.key(), item);
        if (existing == null) {
            dirty.set(true);
            return true;
        }
        if (!existing.ability().equals(item.ability())) {
            byKey.put(item.key(), existing.withAbility(item.ability().orElse(null)));
            dirty.set(true);
        }
        return false;
    }

    /** Removes a definition. Items already made from it are unaffected. */
    public boolean undefine(String key) {
        if (key == null || byKey.remove(normalise(key)) == null) {
            return false;
        }
        dirty.set(true);
        return true;
    }

    // ---------------------------------------------------------------------------- asking

    public Optional<CustomItem> byKey(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(normalise(key)));
    }

    /** Everything one plugin defines. */
    public List<CustomItem> ofPlugin(String plugin) {
        if (plugin == null) {
            return List.of();
        }
        String wanted = plugin.trim().toLowerCase(Locale.ROOT);
        return byKey.values().stream().filter(item -> item.plugin().equals(wanted)).toList();
    }

    public List<CustomItem> all() {
        return List.copyOf(byKey.values());
    }

    /** Every key, for a command's tab completion. */
    public List<String> keys() {
        return List.copyOf(byKey.keySet());
    }

    /** What could not be read from the file. */
    public synchronized List<String> problems() {
        return List.copyOf(problems);
    }

    private static String normalise(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------------------- the file

    public boolean isDirty() {
        return dirty.get();
    }

    public void load() {
        synchronized (this) {
            problems.clear();
        }
        // Read first, replace after: a document that cannot be read right now leaves every item as it was,
        // rather than a server whose custom items all vanished because the database blinked.
        java.util.Optional<YamlConfiguration> fresh = store.readIfReadable();
        if (fresh.isEmpty()) {
            carry();
            return;
        }
        YamlConfiguration yaml = fresh.get();
        byKey.clear();
        unread.clear();
        ConfigurationSection section = yaml.getConfigurationSection("items");
        if (section == null) {
            dirty.set(false);
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            try {
                CustomItem item = read(YamlStore.fromPathPart(key), entry);
                byKey.put(item.key(), item);
            } catch (RuntimeException broken) {
                // A block a newer server knows about, or one renamed between versions, is one item
                // lost rather than a file that will not load.
                note("'" + key + "' was skipped (" + broken.getMessage() + ")");
                unread.put(key, entry);
            }
        }
        dirty.set(false);
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

    private static CustomItem read(String key, ConfigurationSection entry) {
        int colon = key.indexOf(':');
        if (colon <= 0 || colon == key.length() - 1) {
            throw new IllegalArgumentException("'" + key + "' is not plugin:name");
        }
        String materialName = entry.getString("material", "");
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            throw new IllegalArgumentException(
                    "this server has no block called '" + materialName + "'");
        }
        CustomItem.Builder built = CustomItem.builder(key.substring(0, colon),
                        key.substring(colon + 1))
                .material(material)
                .name(entry.getString("name"))
                .lore(entry.getStringList("lore"))
                .glowing(entry.getBoolean("glowing"))
                .ability(entry.getString("ability"))
                .recipe(entry.getStringList("recipe"));
        if (entry.contains("model-data")) {
            built.modelData(entry.getInt("model-data"));
        }
        ConfigurationSection tagged = entry.getConfigurationSection("tags");
        if (tagged != null) {
            for (String tag : tagged.getKeys(false)) {
                built.tag(YamlStore.fromPathPart(tag), String.valueOf(tagged.get(tag)));
            }
        }
        return built.build();
    }

    /** Writes, if anything changed. Via a temporary file, so a kill mid-write cannot truncate it. */
    public void flush() {
        synchronized (flushing) {
            writeChanges();
        }
    }

    /** Entries the file had that this server could not read, kept to be written back untouched. */
    private final Map<String, ConfigurationSection> unread = new ConcurrentHashMap<>();

    /**
     * One flush at a time. The saving timer can still be mid-flush when shutdown flushes by hand, and
     * two at once either land out of order or answer "written" while the other still holds the rows.
     */
    private final Object flushing = new Object();

    private void writeChanges() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        List<CustomItem> snapshot = List.copyOf(byKey.values());
        boolean written = store.write(yaml -> {
            for (CustomItem item : snapshot) {
                String path = "items." + YamlStore.asPathPart(item.key()) + ".";
                yaml.set(path + "material", item.material().name());
                if (!item.name().isEmpty()) {
                    yaml.set(path + "name", item.name());
                }
                if (!item.lore().isEmpty()) {
                    yaml.set(path + "lore", item.lore());
                }
                item.modelData().ifPresent(data -> yaml.set(path + "model-data", data));
                if (item.isGlowing()) {
                    yaml.set(path + "glowing", true);
                }
                item.ability().ifPresent(ability -> yaml.set(path + "ability", ability));
                if (item.isCraftable()) {
                    yaml.set(path + "recipe", item.recipe());
                }
                item.tags().forEach((tag, value) -> yaml.set(path + "tags." + YamlStore.asPathPart(tag), value));
            }
            // Written back as they were found. Skipped is not deleted: an item made on a newer server
            // reads again there, and saving the rest must not take it out of the file.
            unread.forEach((key, kept) -> {
                if (!byKey.containsKey(normalise(YamlStore.fromPathPart(key)))) {
                    yaml.set("items." + key, kept);
                }
            });
        });
        if (!written) {
            dirty.set(true);
        }
    }
}
