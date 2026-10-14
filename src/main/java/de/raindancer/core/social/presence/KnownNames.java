package de.raindancer.core.social.presence;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every player's last name, kept by Core for as long as the server exists.
 *
 * <p>Paper's own name cache ({@code usercache.json}) keeps a limited number of names for a limited time, so a
 * player away for a while drops out of it — and every command that looked them up by name said the server had
 * never seen them, while tab completion, reading their player file, still offered the name. Thread-safe.
 */
public final class KnownNames {

    private final YamlStore file;
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<String, UUID> ids = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public KnownNames(Path file) {
        // One directory of names for the network, each server adding the names it saw; read again when another adds.
        this.file = YamlStore.network(file);
        this.file.onChange(() -> {
            if (dirty) {
                save();
            }
            load();
        });
    }

    public void load() {
        YamlConfiguration yaml = file.read();
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            try {
                put(UUID.fromString(key), players.getString(key));
            } catch (IllegalArgumentException notAnId) {
                // Hand-edited; skipped.
            }
        }
        dirty = false;
    }

    /** Somebody is called this now — on every join, and once for everybody the server has a file for. */
    public synchronized void seen(UUID player, String name) {
        if (player == null || name == null || name.isBlank() || name.equals(names.get(player))) {
            return;
        }
        put(player, name);
        dirty = true;
    }

    /**
     * Only when nothing is known about them yet, and only a name nobody holds — for seeding from the server's
     * player files, which may be years stale. A stale name taking one somebody holds now would point every
     * command naming that player (a ban!) at whoever had it before.
     */
    public synchronized void seenIfUnknown(UUID player, String name) {
        if (player != null && name != null && !names.containsKey(player) && !ids.containsKey(key(name))) {
            seen(player, name);
        }
    }

    private synchronized void put(UUID player, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        String before = names.put(player, name);
        if (before != null) {
            ids.remove(key(before), player);
        }
        UUID previousOwner = ids.put(key(name), player);
        if (previousOwner != null && !previousOwner.equals(player)) {
            names.remove(previousOwner, names.get(previousOwner));
        }
    }

    public Optional<UUID> idOf(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(ids.get(key(name.trim())));
    }

    public Optional<String> nameOf(UUID player) {
        return Optional.ofNullable(player == null ? null : names.get(player));
    }

    public Map<UUID, String> all() {
        return Map.copyOf(names);
    }

    public boolean isDirty() {
        return dirty;
    }

    public boolean save() {
        dirty = false;
        Map<UUID, String> snapshot = new HashMap<>(names);
        boolean saved = file.write(yaml -> snapshot.forEach((id, name) -> yaml.set("players." + id, name)));
        if (!saved) {
            dirty = true;
        }
        return saved;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
