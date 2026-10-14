package de.raindancer.core.data.settings;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * One plugin's settings, bound to a file.
 *
 * <h2>The snapshot</h2>
 * {@link #current()} hands back an immutable record. A caller may hold it for the length of a
 * method, and should not hold it for the length of the server: it is what the settings were when it
 * was taken. Anything that has to react to a change registers with {@link #onChange}, which is
 * cheaper and more obvious than re-reading a config object on every tick.
 *
 * <h2>What happens to a file with mistakes in it</h2>
 * A server owner edits this in a text editor, with the server off, and gets things wrong. None of
 * that may stop the server starting, and none of it may quietly reset the settings around it:
 *
 * <ul>
 *   <li>a value of the wrong type falls back to the default — that one setting, nothing else;</li>
 *   <li>a number outside its range is pulled inside it, rather than being thrown away;</li>
 *   <li>a key that is missing takes its default and is written back on the next save;</li>
 *   <li>a key nobody recognises is <em>left alone</em>, so downgrading and upgrading again does not
 *       delete a setting a newer version added;</li>
 *   <li>a file that is not YAML at all leaves every default in place.</li>
 * </ul>
 *
 * Every one of those is recorded in {@link #problems()} and logged, because a setting silently
 * ignored is a setting somebody will spend an evening on.
 *
 * <h2>Why a typed command is stricter than a file</h2>
 * {@link #set} refuses what {@link #load} would have clamped. The person typing is standing there
 * and can be told; the person who edited the file has gone to bed. See {@link SettingCodec}.
 */
public final class SettingsStore<T> {

    private static final LogChannel log = Log.of("settings");

    private final SettingsSchema<T> schema;
    private final Path file;
    private final YamlStore store;
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
    private final List<String> problems = new ArrayList<>();

    private volatile T current;

    public SettingsStore(SettingsSchema<T> schema, Path file) {
        this(schema, file, de.raindancer.core.data.store.Documents.installed().orElse(null), Layers.group());
    }

    /**
     * Settings in {@code documents}: layered — network, group, this server — when they belong to a server of a
     * network (its scope is not blank); one document per server, as in 1.x, otherwise.
     *
     * @param group this server's group on its network; blank for none
     */
    public SettingsStore(SettingsSchema<T> schema, Path file, de.raindancer.core.data.store.Documents documents,
                         String group) {
        this.schema = schema;
        this.file = file;
        this.layered = documents != null && !documents.serverScope().isBlank() && file != null;
        this.store = documents == null ? new YamlStore(file)
                : new YamlStore(file, documents, de.raindancer.core.data.store.Documents.Scope.SERVER);
        if (layered) {
            // The file of 1.x is this server's: only the server's document brings it in, never the network's.
            this.networkStore = YamlStore.withoutFile(file, documents, de.raindancer.core.data.store.Documents.Scope.NETWORK);
            this.groupStore = group == null || group.isBlank() ? null
                    : YamlStore.withoutFile(file.resolveSibling(file.getFileName() + ".group-" + group),
                    documents, de.raindancer.core.data.store.Documents.Scope.NETWORK);
            // Another server changed the network's settings or this group's: read again, listeners told.
            this.networkStore.onChange(this::load);
            if (groupStore != null) {
                this.groupStore.onChange(this::load);
            }
        } else {
            this.networkStore = null;
            this.groupStore = null;
        }
        // Usable before load(): a plugin that reads a setting while starting up gets the default
        // rather than a NullPointerException.
        this.current = schema.defaults();
    }

    // ---------------------------------------------------------------------------- layers

    /**
     * Where a setting's value comes from on a network: the network's (every server), this server's group's, or
     * this server's alone. The most specific layer that has a value wins.
     */
    public enum Layer { NETWORK, GROUP, SERVER }

    /** Marks a document of overrides, as opposed to a 1.x config.yml that still holds every setting. */
    static final String OVERRIDES = "~overrides";

    private final boolean layered;
    private final YamlStore networkStore;
    private final YamlStore groupStore;
    private final Map<String, Object> networkValues = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Object> groupOverrides = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Object> serverOverrides = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * What this server changed in the network's and the group's documents since it last wrote them. Only these go
     * out: writing every value would put this server's older copy back over what another server changed meanwhile.
     */
    private final java.util.Set<String> networkChanged = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> groupChanged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Whether this store keeps layers — a server of a network — rather than one document. */
    public boolean isLayered() {
        return layered;
    }

    /** The layer a setting's value comes from here. Always {@link Layer#NETWORK} when not layered. */
    public Layer layerOf(String key) {
        if (serverOverrides.containsKey(key)) {
            return Layer.SERVER;
        }
        return groupOverrides.containsKey(key) ? Layer.GROUP : Layer.NETWORK;
    }

    /** Which group the stores made from now on belong to. Set once by Core from network.yml. */
    public static final class Layers {
        private static volatile String group = "";

        private Layers() {
        }

        public static void group(String name) {
            group = name == null ? "" : name;
        }

        static String group() {
            return group;
        }
    }

    public SettingsSchema<T> schema() {
        return schema;
    }

    /** The settings as they are now. Immutable; take a fresh one rather than holding this. */
    public T current() {
        return current;
    }

    /** What was wrong with the file, in the words the log used. Empty when it was clean. */
    public synchronized List<String> problems() {
        return List.copyOf(problems);
    }

    // ---------------------------------------------------------------------------- loading

    /**
     * Reads the file, or falls back to defaults when there is not one.
     *
     * <p>Also used to reload: listeners are told if anything actually changed.
     */
    public void load() {
        if (layered) {
            loadLayers();
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> found = new ArrayList<>();
        YamlConfiguration yaml = readFile(found);

        for (Setting<?> setting : schema.settings()) {
            values.put(setting.key(), valueFrom(yaml, setting, found));
        }

        synchronized (this) {
            problems.clear();
            problems.addAll(found);
        }
        for (String problem : found) {
            log.warn("{}: {}", file.getFileName(), problem);
        }
        publish(schema.instantiate(values));
    }

    /** The file as YAML, or an empty configuration when it is missing or unreadable. */
    /**
     * Network, group and this server's overrides — and, the first time on a network, this server's 1.x
     * config.yml shared out: it becomes the network's when there is none yet, otherwise only what differs from
     * the network's stays as this server's. Either way every setting is what it was before.
     */
    private synchronized void loadLayers() {
        List<String> found = new ArrayList<>();
        // The server's first: it is the one that brings a 1.x file in.
        java.util.Optional<YamlConfiguration> server = store.readIfReadable();
        java.util.Optional<YamlConfiguration> network = networkStore.readIfReadable();
        java.util.Optional<YamlConfiguration> group = groupStore == null ? java.util.Optional.of(new YamlConfiguration())
                : groupStore.readIfReadable();
        if (network.isEmpty() || server.isEmpty() || group.isEmpty()) {
            // Not read is not "nothing set": what is in force stays, rather than every setting falling to its default.
            for (String problem : java.util.stream.Stream.of(networkStore, store, groupStore)
                    .filter(Objects::nonNull).flatMap(each -> each.problems().stream()).toList()) {
                found.add("the settings " + problem + ", so the settings in force are kept");
            }
            synchronized (this) {
                problems.clear();
                problems.addAll(found);
            }
            found.forEach(problem -> log.warn("{}: {}", file.getFileName(), problem));
            return;
        }
        Map<String, Object> networkRead = new LinkedHashMap<>();
        for (Setting<?> setting : schema.settings()) {
            networkRead.put(setting.key(), valueFrom(network.get(), setting, found));
        }
        Map<String, Object> groupRead = overridesIn(group.get(), found);
        Map<String, Object> serverRead;
        boolean legacy = !server.get().getKeys(false).isEmpty() && !server.get().contains(OVERRIDES);
        if (legacy) {
            Map<String, Object> theirs = new LinkedHashMap<>();
            for (Setting<?> setting : schema.settings()) {
                theirs.put(setting.key(), valueFrom(server.get(), setting, found));
            }
            if (network.get().getKeys(false).isEmpty()) {
                // The first server of the network: its settings are the network's.
                networkRead.putAll(theirs);
                serverRead = new LinkedHashMap<>();
                log.info("{}: this server's settings are now the network's.", file.getFileName());
            } else {
                // Another server came first: this one keeps, as its own, only what it had set differently.
                serverRead = new LinkedHashMap<>();
                theirs.forEach((key, value) -> {
                    Object shared = groupRead.containsKey(key) ? groupRead.get(key) : networkRead.get(key);
                    if (!Objects.equals(shared, value)) {
                        serverRead.put(key, value);
                    }
                });
                log.info("{}: {} setting(s) differ from the network's and stay this server's own.",
                        file.getFileName(), serverRead.size());
            }
        } else {
            serverRead = overridesIn(server.get(), found);
        }
        // What this server changed and has not written yet stays: it is newer than what was just read.
        networkChanged.forEach(key -> {
            if (networkValues.containsKey(key)) {
                networkRead.put(key, networkValues.get(key));
            }
        });
        groupChanged.forEach(key -> {
            if (groupOverrides.containsKey(key)) {
                groupRead.put(key, groupOverrides.get(key));
            } else {
                groupRead.remove(key);
            }
        });
        networkValues.clear();
        networkValues.putAll(networkRead);
        groupOverrides.clear();
        groupOverrides.putAll(groupRead);
        serverOverrides.clear();
        serverOverrides.putAll(serverRead);
        synchronized (this) {
            problems.clear();
            problems.addAll(found);
        }
        found.forEach(problem -> log.warn("{}: {}", file.getFileName(), problem));
        if (legacy) {
            // Written out now, so the next start finds layers rather than a 1.x file again — every network value
            // when this server's settings became the network's, otherwise only its own.
            if (network.get().getKeys(false).isEmpty()) {
                schema.settings().forEach(setting -> networkChanged.add(setting.key()));
            }
            saveLayers();
        }
        publish(effective());
    }

    private Map<String, Object> overridesIn(YamlConfiguration yaml, List<String> found) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (Setting<?> setting : schema.settings()) {
            if (yaml.contains(setting.key())) {
                overrides.put(setting.key(), valueFrom(yaml, setting, found));
            }
        }
        return overrides;
    }

    /** What every setting is here: this server's override, else the group's, else the network's. */
    private T effective() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Setting<?> setting : schema.settings()) {
            String key = setting.key();
            values.put(key, serverOverrides.containsKey(key) ? serverOverrides.get(key)
                    : groupOverrides.containsKey(key) ? groupOverrides.get(key)
                    : networkValues.getOrDefault(key, setting.defaultValue()));
        }
        return schema.instantiate(values);
    }

    private boolean saveLayers() {
        java.util.Set<String> network = de.raindancer.core.platform.util.Marks.drain(networkChanged);
        java.util.Set<String> group = de.raindancer.core.platform.util.Marks.drain(groupChanged);
        boolean networkWritten = networkStore.update(yaml -> {
            for (Setting<?> setting : schema.settings()) {
                // Every setting has its line and its comment in the network's document; only changed values are set.
                if (network.contains(setting.key()) || !yaml.contains(setting.key())) {
                    yaml.set(setting.key(), SettingCodec.toYaml(networkValues.getOrDefault(setting.key(), setting.defaultValue())));
                }
                yaml.setComments(setting.key(), commentFor(setting));
            }
        });
        boolean groupWritten = groupStore == null || group.isEmpty() || groupStore.update(yaml -> {
            yaml.set(OVERRIDES, true);
            for (String key : group) {
                schema.setting(key).ifPresent(setting -> yaml.set(key, groupOverrides.containsKey(key)
                        ? SettingCodec.toYaml(groupOverrides.get(key)) : null));
            }
        });
        boolean serverWritten = store.write(yaml -> writeOverrides(yaml, serverOverrides));
        if (!networkWritten) {
            networkChanged.addAll(network);
        }
        if (!groupWritten) {
            groupChanged.addAll(group);
        }
        return networkWritten && groupWritten && serverWritten;
    }

    private void writeOverrides(YamlConfiguration yaml, Map<String, Object> overrides) {
        yaml.set(OVERRIDES, true);
        yaml.setComments(OVERRIDES, List.of("Only the settings that differ from the network's; the rest are in the",
                "network's config.yml. Change them with /settings."));
        for (Setting<?> setting : schema.settings()) {
            if (overrides.containsKey(setting.key())) {
                yaml.set(setting.key(), SettingCodec.toYaml(overrides.get(setting.key())));
                yaml.setComments(setting.key(), commentFor(setting));
            }
        }
    }

    /**
     * Sets {@code key} at {@code layer} — the whole network's value, this group's, or this server's own.
     * Refused (false) for an unknown key or a value that does not fit. Saved by {@link #save()}.
     */
    public boolean set(String key, String raw, Layer layer) {
        Setting<?> setting = schema.setting(key).orElse(null);
        if (setting == null) {
            return false;
        }
        Optional<Object> parsed = SettingCodec.fromText(setting, raw);
        if (parsed.isEmpty() || !SettingCodec.isInRange(setting, parsed.get())) {
            return false;
        }
        return writeAt(setting, parsed.get(), layered ? layer : Layer.NETWORK);
    }

    /** Takes a group's or this server's own value away, so the next layer's applies again. */
    public void clearOverride(String key, Layer layer) {
        if (!layered || layer == Layer.NETWORK) {
            return;
        }
        (layer == Layer.SERVER ? serverOverrides : groupOverrides).remove(key);
        if (layer == Layer.GROUP) {
            groupChanged.add(key);
        }
        publish(effective());
    }

    private boolean writeAt(Setting<?> setting, Object value, Layer layer) {
        if (!layered) {
            write(setting, value);
            return true;
        }
        switch (layer) {
            case SERVER -> serverOverrides.put(setting.key(), value);
            case GROUP -> {
                if (groupStore == null) {
                    // No group: refused, never quietly the network's — the right to change a group is not the
                    // right to change every server.
                    return false;
                }
                groupOverrides.put(setting.key(), value);
                groupChanged.add(setting.key());
            }
            case NETWORK -> {
                networkValues.put(setting.key(), value);
                networkChanged.add(setting.key());
            }
        }
        publish(effective());
        return true;
    }

    private YamlConfiguration readFile(List<String> found) {
        YamlConfiguration yaml = store.read();
        // Not fatal on purpose. A broken file means this plugin runs on its defaults for one start,
        // which is far better than a server that will not come up at all — and the file is never
        // written over: a save is refused while it cannot be read (YamlStore.update), so nothing the
        // owner wrote is lost, and the next save after they fix it goes through.
        for (String problem : store.problems()) {
            found.add("the file " + problem + ", so every setting is at its default");
        }
        return yaml;
    }

    /** One setting's value out of the file, with everything that can be wrong with it handled. */
    private Object valueFrom(YamlConfiguration yaml, Setting<?> setting, List<String> found) {
        if (!yaml.contains(setting.key())) {
            return setting.defaultValue();
        }
        Optional<Object> parsed = SettingCodec.fromYaml(setting, yaml.get(setting.key()));
        if (parsed.isEmpty()) {
            found.add(setting.key() + " is not a " + readableType(setting) + " ('"
                    + yaml.get(setting.key()) + "'), so the default "
                    + SettingCodec.display(setting.defaultValue()) + " is used");
            return setting.defaultValue();
        }
        Object value = parsed.get();
        if (!SettingCodec.isInRange(setting, value)) {
            Object clamped = SettingCodec.clamp(setting, value);
            found.add(setting.key() + " is " + SettingCodec.display(value) + ", outside "
                    + setting.min() + "–" + setting.max() + ", so "
                    + SettingCodec.display(clamped) + " is used");
            return clamped;
        }
        return value;
    }

    // ---------------------------------------------------------------------------- saving

    /**
     * Writes every setting back, with its documentation above it.
     *
     * <p>Keys the schema does not know are kept: a server that downgrades and upgrades again must
     * not lose a setting the newer version added. That is also why this loads the existing file
     * first rather than writing a fresh one.
     */
    public void save() {
        trySave();
    }

    /** The file these settings live in. */
    public Path file() {
        return file;
    }

    /**
     * {@link #save()}, saying whether it landed.
     *
     * @return false when the file on disk could not be read, so it was left alone for the owner to fix
     *         rather than overwritten — the values are in effect until a restart, and the next save
     *         after the file is fixed writes them
     */
    public boolean trySave() {
        if (layered) {
            return saveLayers();
        }
        // An update rather than a write: that is what keeps the unknown keys and whatever the owner
        // wrote around them. It is also atomic, so a server killed here has the old config or the
        // new one and never half of a file it needs to start. The values are read inside it, under
        // the file's lock, so of two saves racing each other the newer values land last.
        return store.update(yaml -> {
            T snapshot = current;
            for (Setting<?> setting : schema.settings()) {
                yaml.set(setting.key(), SettingCodec.toYaml(setting.valueIn(snapshot)));
                yaml.setComments(setting.key(), commentFor(setting));
            }
        });
    }

    /**
     * The lines above a key in the file: what it is, what it does, and what it may be.
     *
     * <p>So the file can be edited without the source open, which is the only way most server
     * owners will ever meet these settings.
     */
    private List<String> commentFor(Setting<?> setting) {
        List<String> lines = new ArrayList<>();
        SettingsTopic topic = schema.topics().at(setting.topicPath()).orElse(null);
        if (topic != null && isFirstOf(topic, setting) && !topic.description().isBlank()) {
            lines.add("");
            lines.add("--- " + topic.title() + " ---");
            lines.add(topic.description());
        }
        lines.add(setting.title());
        if (!setting.description().isBlank()) {
            lines.add(setting.description());
        }
        if (setting.min() != null) {
            lines.add("From " + setting.min() + " to " + setting.max() + ".");
        }
        if (!setting.choices().isEmpty()) {
            lines.add("One of: " + String.join(", ", setting.choices()) + ".");
        }
        return lines;
    }

    private static boolean isFirstOf(SettingsTopic topic, Setting<?> setting) {
        List<Setting<?>> siblings = topic.settings();
        return !siblings.isEmpty() && siblings.getFirst().key().equals(setting.key());
    }

    // ---------------------------------------------------------------------------- changing

    /**
     * Applies text somebody typed.
     *
     * @return whether it was accepted; false leaves everything as it was
     */
    public boolean set(String key, String raw) {
        Setting<?> setting = schema.setting(key).orElse(null);
        if (setting == null) {
            return false;
        }
        Optional<Object> parsed = SettingCodec.fromText(setting, raw);
        // Refused rather than clamped: whoever typed this is standing there and can be told.
        if (parsed.isEmpty() || !SettingCodec.isInRange(setting, parsed.get())) {
            return false;
        }
        // At the layer the value comes from now: changing the network's value under a server's own would
        // change nothing anybody here could see.
        writeAt(setting, parsed.get(), layerOf(key));
        return true;
    }

    /** Flips a flag or advances a choice, and answers with the value it landed on. */
    public Object cycle(String key) {
        Setting<?> setting = schema.setting(key).orElse(null);
        if (setting == null) {
            return null;
        }
        Object next = SettingCodec.cycle(setting, setting.valueIn(current));
        writeAt(setting, next, layerOf(key));
        return next;
    }

    /** Puts one setting back to what the plugin shipped with. */
    public void reset(String key) {
        schema.setting(key).ifPresent(setting -> writeAt(setting, setting.defaultValue(), layerOf(key)));
    }

    /** Puts all of them back. */
    public void resetAll() {
        if (layered) {
            networkValues.clear();
            schema.settings().forEach(setting -> networkChanged.add(setting.key()));
            publish(effective());
            return;
        }
        publish(schema.defaults());
    }

    /** What a person should see for this setting right now. Empty for a key nobody declared. */
    public String display(String key) {
        return schema.setting(key)
                .map(setting -> SettingCodec.display(setting.valueIn(current)))
                .orElse("");
    }

    private void write(Setting<?> setting, Object value) {
        Map<String, Object> values = new LinkedHashMap<>();
        T snapshot = current;
        for (Setting<?> each : schema.settings()) {
            values.put(each.key(), each.key().equals(setting.key())
                    ? value
                    : each.valueIn(snapshot));
        }
        publish(schema.instantiate(values));
    }

    // ---------------------------------------------------------------------------- listeners

    /**
     * Tells this listener whenever the settings change — by a command, by a click, or by a reload.
     *
     * <p>Only when something actually changed: setting a value to what it already was does not wake
     * anybody up, so a listener may rebuild something expensive without checking first.
     */
    public void onChange(Consumer<T> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void publish(T updated) {
        if (Objects.equals(current, updated)) {
            return;
        }
        current = updated;
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(updated);
            } catch (RuntimeException broken) {
                // One plugin's listener must not stop another's, and must not stop the save that
                // usually follows a change.
                log.error(broken, "A settings listener for {} threw.", schema.id());
            }
        }
    }

    private static String readableType(Setting<?> setting) {
        Class<?> type = setting.type();
        if (type == Boolean.class) {
            return "true/false value";
        }
        if (type == Integer.class || type == Long.class) {
            return "whole number";
        }
        if (type == Double.class) {
            return "number";
        }
        if (type == List.class) {
            return "list";
        }
        if (!setting.choices().isEmpty()) {
            return "one of " + String.join(", ", setting.choices());
        }
        return type.getSimpleName();
    }
}
