package de.raindancer.core.data.settings;

import de.raindancer.core.platform.util.PluginCode;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Material;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every plugin's settings, merged into one tree.
 *
 * <h2>Why they merge rather than sitting side by side</h2>
 * A player opening the settings does not know which of nine jars owns the thing they are looking
 * for, and should not have to. Nine menus, one per plugin, is exactly the wall this refactor exists
 * to remove. So the categories merge: claims and the ghast lines both putting something under
 * {@code config/limits} produce one Limits page with both settings on it, and neither plugin knows
 * the other exists.
 *
 * <h2>The one thing merging cannot paper over</h2>
 * Two plugins using the same key. A command saying {@code /settings set cruise-speed 7} has to
 * reach exactly one setting, and if two answer to that name then whichever was registered first
 * wins — silently, and differently depending on load order. So clashes are <em>reported</em>
 * ({@link #clashes()}), the ambiguous short name keeps working for whichever came first, and both
 * remain reachable by their full {@code plugin:key} name. Nothing is hidden and nothing is lost.
 */
public final class SettingsRegistry {

    private static final LogChannel log = Log.of("settings");

    private final List<SettingsStore<?>> stores = new CopyOnWriteArrayList<>();

    private final java.util.Set<String> toldAbout = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Adds a plugin's settings to the combined tree. */
    public void add(SettingsStore<?> store) {
        if (store == null) {
            return;
        }
        stores.add(store);
        for (Map.Entry<String, List<String>> clash : clashes().entrySet()) {
            // Nothing is wrong: plugin:key reaches either. Said once per name, for whoever reads the debug log.
            if (toldAbout.add(clash.getKey() + "=" + clash.getValue())) {
                log.debug("The setting '{}' is declared by more than one plugin ({}). A command using "
                                + "that name alone reaches the first; use plugin:key to be sure.",
                        clash.getKey(), String.join(", ", clash.getValue()));
            }
        }
    }

    /**
     * Drops whatever {@code loader}'s code registered here — for a plugin disabled without taking its
     * own back. See {@link PluginCode}.
     *
     * @return how many were dropped
     */
    public int forgetFrom(ClassLoader loader) {
        int before = stores.size();
        stores.removeIf(store -> PluginCode.loaded(store.schema().type(), loader));
        return before - stores.size();
    }

    public List<SettingsStore<?>> stores() {
        return List.copyOf(stores);
    }

    /**
     * Takes a plugin's settings back out of the combined tree.
     *
     * <p>The counterpart {@link #add} never had to have: nothing used to unregister a plugin's settings
     * short of the whole server stopping. A module hosted inside another plugin changed that — its
     * settings can be bound while it is starting and then have to disappear again, either because the
     * rest of {@code enable()} throws or because it is later disabled — and without a way to take them
     * back out, a module that never finished starting, or one that has since stopped, keeps its page in
     * {@code /settings} for as long as the server runs. Harmless to call with a store this registry
     * never held, or twice.
     */
    public void remove(SettingsStore<?> store) {
        if (store == null) {
            return;
        }
        stores.remove(store);
    }

    // ---------------------------------------------------------------------------- the tree

    /**
     * The combined tree, built fresh each time.
     *
     * <p>Rebuilt rather than cached because it is asked for when a menu opens — a handful of times
     * an hour — and a cache would need invalidating whenever a plugin is enabled, which is a bug
     * waiting to happen for no measurable gain.
     */
    public SettingsTopics topics() {
        List<Topic> declared = new ArrayList<>();
        for (SettingsStore<?> store : stores) {
            for (SettingsTopic topic : store.schema().topics().all()) {
                declared.add(asDeclaration(topic));
            }
        }
        SettingsTopics merged = new SettingsTopics(declared, "the combined settings", true);
        for (SettingsStore<?> store : stores) {
            for (Setting<?> setting : store.schema().settings()) {
                merged.file(setting, "the combined settings");
            }
        }
        return merged;
    }

    /**
     * One schema's topic, as something {@link SettingsTopics} can be given again.
     *
     * <p>A blank description is passed through as blank on purpose: {@code SettingsTopics} keeps the
     * description a node already has when a later declaration does not supply one, which is what
     * lets a plugin declare a shared category without blanking out the sentence another plugin
     * wrote for it.
     */
    private static Topic asDeclaration(SettingsTopic topic) {
        return new Topic() {
            @Override
            public Class<Topic> annotationType() {
                return Topic.class;
            }

            @Override
            public String path() {
                return topic.path();
            }

            @Override
            public String title() {
                return topic.title();
            }

            @Override
            public Material icon() {
                return topic.icon();
            }

            @Override
            public String description() {
                return topic.description();
            }
        };
    }

    // ---------------------------------------------------------------------------- reading

    /** Every key, short where it is unambiguous. For a command's tab completion. */
    public List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (SettingsStore<?> store : stores) {
            keys.addAll(store.schema().keys());
        }
        return List.copyOf(keys);
    }

    /**
     * Which plugin owns a setting.
     *
     * @param key either the short key, or {@code plugin:key} when two plugins share a name
     */
    public Optional<SettingsStore<?>> storeOf(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String wanted = key.trim();
        int colon = wanted.indexOf(':');
        if (colon > 0) {
            String plugin = wanted.substring(0, colon).toLowerCase(Locale.ROOT);
            String bare = wanted.substring(colon + 1);
            for (SettingsStore<?> store : stores) {
                if (store.schema().id().equals(plugin)
                        && store.schema().setting(bare).isPresent()) {
                    return Optional.of(store);
                }
            }
            return Optional.empty();
        }
        // A plain loop rather than a stream: the wildcard capture that comes out of
        // stores.stream() is a different type on every element as far as the compiler is
        // concerned, and casting it back is noisier than not using a stream at all.
        for (SettingsStore<?> store : stores) {
            if (store.schema().setting(wanted).isPresent()) {
                return Optional.of(store);
            }
        }
        return Optional.empty();
    }

    /** The setting itself, wherever it lives. */
    public Optional<Setting<?>> setting(String key) {
        return storeOf(key).flatMap(store -> store.schema().setting(bareKey(key)));
    }

    /** What it currently says, for a menu or a command. Empty when nothing answers to that key. */
    public String display(String key) {
        return storeOf(key).map(store -> store.display(bareKey(key))).orElse("");
    }

    // ---------------------------------------------------------------------------- writing

    /** Applies typed text to whichever plugin owns the setting. */
    public boolean set(String key, String raw) {
        return storeOf(key).map(store -> store.set(bareKey(key), raw)).orElse(false);
    }

    /** Sets one setting at a layer — the network's, this group's, or this server's own. */
    public boolean set(String key, String raw, SettingsStore.Layer layer) {
        return storeOf(key).map(store -> store.set(bareKey(key), raw, layer)).orElse(false);
    }

    /** Takes a group's or this server's own value away, so the next layer's applies again. */
    public void clearOverride(String key, SettingsStore.Layer layer) {
        storeOf(key).ifPresent(store -> store.clearOverride(bareKey(key), layer));
    }

    /** Where a setting's value comes from here; {@link SettingsStore.Layer#NETWORK} on a server of its own. */
    public SettingsStore.Layer layerOf(String key) {
        return storeOf(key).map(store -> store.layerOf(bareKey(key))).orElse(SettingsStore.Layer.NETWORK);
    }

    /** Changing a setting for the whole network, not only the server one is standing on. */
    public static final String NETWORK_PERMISSION = "rainscore.settings.network";
    /** Changing a setting for every server of this server's group. */
    public static final String GROUP_PERMISSION = "rainscore.settings.group";

    /**
     * Whether {@code who} may change {@code key} where its value comes from now. On a network,
     * {@code rainscore.settings} reaches as far as it did in 1.x — this server — and changing what every server
     * runs takes more: a builder server's admin must not reconfigure the survival server. Null is code or the
     * console, which may.
     */
    public boolean mayChange(org.bukkit.permissions.Permissible who, String key) {
        return mayChangeAt(who, key, layerOf(key));
    }

    /** As {@link #mayChange}, for a change aimed at {@code layer}. */
    public boolean mayChangeAt(org.bukkit.permissions.Permissible who, String key, SettingsStore.Layer layer) {
        if (who == null || !isLayered(key)) {
            return true;
        }
        return switch (layer) {
            case SERVER -> true;
            case GROUP -> who.hasPermission(GROUP_PERMISSION) || who.hasPermission(NETWORK_PERMISSION);
            case NETWORK -> who.hasPermission(NETWORK_PERMISSION);
        };
    }

    /** Whether settings here come in layers — this is a server of a network. */
    public boolean isLayered(String key) {
        return storeOf(key).map(SettingsStore::isLayered).orElse(false);
    }

    /** Flips a flag or advances a choice, wherever it lives. */
    public Object cycle(String key) {
        return storeOf(key).map(store -> store.cycle(bareKey(key))).orElse(null);
    }

    /** Puts one setting back to what its plugin shipped with. */
    public void reset(String key) {
        storeOf(key).ifPresent(store -> store.reset(bareKey(key)));
    }

    /** Writes every plugin's file. */
    public void saveAll() {
        saveAllChecked();
    }

    /**
     * Writes every plugin's file, and says which could not be written — a file somebody broke by hand
     * is left as it is rather than overwritten, so a change made now lasts only until a restart.
     *
     * @return the files not written; empty when everything was saved
     */
    public List<Path> saveAllChecked() {
        List<Path> unwritten = new ArrayList<>();
        for (SettingsStore<?> store : stores) {
            if (!store.trySave()) {
                unwritten.add(store.file());
            }
        }
        return List.copyOf(unwritten);
    }

    // ---------------------------------------------------------------------------- clashes

    /**
     * Keys more than one plugin declares, and who declares them.
     *
     * <p>Empty on a healthy server. Worth showing at startup and in a diagnostic command, because
     * the symptom otherwise is a command that changes the wrong plugin's setting depending on which
     * jar happened to load first.
     */
    public Map<String, List<String>> clashes() {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (SettingsStore<?> store : stores) {
            for (String key : store.schema().keys()) {
                byKey.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(store.schema().id());
            }
        }
        Map<String, List<String>> clashing = new LinkedHashMap<>();
        byKey.forEach((key, plugins) -> {
            if (plugins.size() > 1) {
                clashing.put(key, List.copyOf(plugins));
            }
        });
        return Map.copyOf(clashing);
    }

    private static String bareKey(String key) {
        int colon = key == null ? -1 : key.indexOf(':');
        return colon > 0 ? key.substring(colon + 1) : key;
    }
}
