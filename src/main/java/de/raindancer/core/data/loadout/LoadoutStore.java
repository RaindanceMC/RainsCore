package de.raindancer.core.data.loadout;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loadouts on disk, one file per player ({@code <uuid>.yml}), one section per named profile.
 *
 * <p>Items stay encoded all the way through, so nothing here can lose one this server cannot read —
 * that is decided by {@link Loadouts#apply}, which refuses rather than drops.
 */
public final class LoadoutStore {

    private final Path folder;
    private final Map<UUID, YamlStore> files = new ConcurrentHashMap<>();

    public LoadoutStore(Path folder) {
        this.folder = folder;
    }

    /** @return whether it reached disk; a file that could not be read is left alone and this is false */
    public boolean save(UUID owner, String profile, Loadout loadout) {
        String key = YamlStore.asPathPart(profile);
        return file(owner).update(yaml -> {
            yaml.set(key, null);
            ConfigurationSection section = yaml.createSection(key);
            section.set("inventory", loadout.inventory());
            section.set("ender-chest", loadout.enderChest());
            section.set("level", loadout.level());
            section.set("exp", (double) loadout.exp());
            section.set("health", loadout.health());
            section.set("food", loadout.food());
            section.set("saturation", (double) loadout.saturation());
            section.set("game-mode", loadout.gameMode());
            section.set("allow-flight", loadout.allowFlight());
            section.set("flying", loadout.flying());
            List<String> effects = new ArrayList<>();
            for (Loadout.Effect effect : loadout.effects()) {
                effects.add(String.join(" ", effect.type(), String.valueOf(effect.ticks()),
                        String.valueOf(effect.amplifier()), String.valueOf(effect.ambient()),
                        String.valueOf(effect.particles()), String.valueOf(effect.icon())));
            }
            section.set("effects", effects);
            if (loadout.data() != null) {
                // Written even when empty: "this side has none" is not "saved before side data".
                section.createSection("data");
                loadout.data().forEach((id, value) -> section.set("data." + YamlStore.asPathPart(id), value));
            }
            Loadout.Place place = loadout.place();
            if (place != null) {
                section.set("place.world", place.world());
                section.set("place.x", place.x());
                section.set("place.y", place.y());
                section.set("place.z", place.z());
                section.set("place.yaw", (double) place.yaw());
                section.set("place.pitch", (double) place.pitch());
            }
        });
    }

    /** The side data of a saved loadout; null for one saved before there was any. */
    private static java.util.Map<String, String> data(ConfigurationSection section) {
        ConfigurationSection data = section.getConfigurationSection("data");
        if (data == null) {
            return null;
        }
        java.util.Map<String, String> read = new java.util.LinkedHashMap<>();
        for (String key : data.getKeys(false)) {
            String value = data.getString(key);
            if (value != null) {
                read.put(YamlStore.fromPathPart(key), value);
            }
        }
        return read;
    }

    public Optional<Loadout> load(UUID owner, String profile) {
        YamlConfiguration yaml = file(owner).read();
        ConfigurationSection section = yaml.getConfigurationSection(YamlStore.asPathPart(profile));
        if (section == null) {
            return Optional.empty();
        }
        List<Loadout.Effect> effects = new ArrayList<>();
        for (String line : section.getStringList("effects")) {
            String[] parts = line.split(" ");
            if (parts.length == 6) {
                try {
                    effects.add(new Loadout.Effect(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                            Boolean.parseBoolean(parts[3]), Boolean.parseBoolean(parts[4]),
                            Boolean.parseBoolean(parts[5])));
                } catch (NumberFormatException skipped) {
                    // An effect somebody mistyped by hand is one effect fewer, not a lost loadout.
                }
            }
        }
        Loadout.Place place = section.isString("place.world")
                ? new Loadout.Place(section.getString("place.world"), section.getDouble("place.x"),
                section.getDouble("place.y"), section.getDouble("place.z"),
                (float) section.getDouble("place.yaw"), (float) section.getDouble("place.pitch"))
                : null;
        return Optional.of(new Loadout(section.getStringList("inventory"), section.getStringList("ender-chest"),
                section.getInt("level"), (float) section.getDouble("exp"), section.getDouble("health", 20),
                section.getInt("food", 20), (float) section.getDouble("saturation", 5),
                section.getString("game-mode"), section.getBoolean("allow-flight"), section.getBoolean("flying"),
                effects, place, data(section)));
    }

    /**
     * Whether this player's file can be read — false when it exists and is broken. {@link #has} and
     * {@link #load} answer "nothing" for a broken file too; a caller deciding something from "nothing" (is this
     * player in admin mode?) asks this first, so a broken file never counts as an empty one.
     */
    public boolean readable(UUID owner) {
        YamlStore store = file(owner);
        store.read();
        return store.problems().isEmpty();
    }

    public boolean has(UUID owner, String profile) {
        return file(owner).read().isConfigurationSection(YamlStore.asPathPart(profile));
    }

    /** @return whether there was such a profile to delete */
    public boolean delete(UUID owner, String profile) {
        String key = YamlStore.asPathPart(profile);
        if (!has(owner, profile)) {
            return false;
        }
        return file(owner).update(yaml -> yaml.set(key, null));
    }

    private YamlStore file(UUID owner) {
        // A player's loadouts are theirs on every server of the network; read fresh on every load.
        return files.computeIfAbsent(owner, id -> YamlStore.network(folder.resolve(id + ".yml")));
    }
}
