package de.raindancer.core.data.store;

import de.raindancer.core.platform.network.NetworkBus;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * One value per key under a section of a document — a player's choices, a player's unlocks — kept right on a
 * network: a change starts from what the database holds for that key, not from this server's copy, and writes
 * that key alone, so two servers changing it at once both count and no other key is put back to a stale value.
 * Another server's change is read in and reported with the keys it touched; a document that cannot be read
 * keeps what is known and refuses every change until it can.
 *
 * <p>The pattern many modules wrote by hand ({@code readIfReadable}, {@code update}, {@code onChange}), once.
 *
 * @param <T> the value; {@code read} answers null for a key with nothing usable under it
 */
public final class KeyedDocument<T> {

    /** Reads the value at {@code path}; null when there is none. */
    @FunctionalInterface
    public interface Reader<T> {
        T read(ConfigurationSection yaml, String path);
    }

    /** Writes {@code value} at {@code path}, which has been cleared first. */
    @FunctionalInterface
    public interface Writer<T> {
        void write(ConfigurationSection yaml, String path, T value);
    }

    private final YamlStore store;
    private final String section;
    private final Reader<T> reader;
    private final Writer<T> writer;
    private final Map<String, T> values = new ConcurrentHashMap<>();
    private final Set<Consumer<Set<String>>> listeners = ConcurrentHashMap.newKeySet();
    private volatile boolean readable = true;

    public KeyedDocument(YamlStore store, String section, Reader<T> reader, Writer<T> writer) {
        this.store = store;
        this.section = section;
        this.reader = reader;
        this.writer = writer;
        // Kept for the store's life, as the store is the owner's.
        store.onChange(this::reload);
    }

    /** Reads everything; false when the document could not be read (what is known stays). */
    public synchronized boolean load() {
        Optional<YamlConfiguration> yaml = store.readIfReadable();
        if (yaml.isEmpty()) {
            readable = false;
            return false;
        }
        readable = true;
        values.clear();
        values.putAll(parse(yaml.get()));
        return true;
    }

    private void reload() {
        Set<String> changed;
        synchronized (this) {
            Map<String, T> before = new HashMap<>(values);
            if (!load()) {
                return;
            }
            changed = new HashSet<>(before.keySet());
            changed.addAll(values.keySet());
            changed.removeIf(key -> Objects.equals(before.get(key), values.get(key)));
        }
        if (!changed.isEmpty()) {
            listeners.forEach(listener -> listener.accept(Set.copyOf(changed)));
        }
    }

    private Map<String, T> parse(YamlConfiguration yaml) {
        Map<String, T> read = new HashMap<>();
        ConfigurationSection under = yaml.getConfigurationSection(section);
        if (under == null) {
            return read;
        }
        for (String key : under.getKeys(false)) {
            T value = reader.read(under, key);
            if (value != null) {
                read.put(key, value);
            }
        }
        return read;
    }

    public Optional<T> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public Map<String, T> all() {
        return Map.copyOf(values);
    }

    public boolean readable() {
        return readable;
    }

    /**
     * Changes one key: {@code change} gets what the database holds for it now (null for nothing) and answers the
     * new value, null to remove it. Retried by the store when another server wrote in between, so {@code change}
     * must only compute.
     *
     * @return whether it was written
     */
    public synchronized boolean change(String key, UnaryOperator<T> change) {
        if (!readable && !load()) {
            return false;
        }
        @SuppressWarnings("unchecked")
        T[] now = (T[]) new Object[1];
        boolean written = store.update(yaml -> {
            String path = section + "." + key;
            ConfigurationSection under = yaml.getConfigurationSection(section);
            now[0] = change.apply(under == null ? null : reader.read(under, key));
            yaml.set(path, null);
            if (now[0] != null) {
                writer.write(yaml, path, now[0]);
            }
        });
        if (written) {
            if (now[0] == null) {
                values.remove(key);
            } else {
                values.put(key, now[0]);
            }
        }
        return written;
    }

    /** Told the keys another server changed, after they are read in. */
    public NetworkBus.Subscription onChange(Consumer<Set<String>> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
