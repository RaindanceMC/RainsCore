package de.raindancer.core.world.build;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.manage.VoidGenerator;
import de.raindancer.core.world.protection.LockedWorlds;
import de.raindancer.core.world.safety.Spot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A schematic built into an empty world of its own — a lobby, a parkour course — centred on the world's middle with
 * its lowest layer at {@link #BASE_Y}, locked so only who {@code mayBuild} changes it. Built once; built again only
 * when the schematic file changes ({@code builtFile} remembers which version stands — a row in Core's database, so
 * read and written off the server thread).
 */
public final class SchematicWorld {

    /** The height the lowest layer is built at; below it lies the void. */
    public static final int BASE_Y = 64;

    /** Where players land: relative to the schematic's lowest corner, or (from {@link #absolute}) in the world. */
    public record Landing(double x, double y, double z, float yaw, float pitch) {

        /** {@code x y z [yaw pitch]}. */
        public static Optional<Landing> parse(String line) {
            if (line == null) {
                return Optional.empty();
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 3) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Landing(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]), parts.length > 3 ? Float.parseFloat(parts[3]) : 0,
                        parts.length > 4 ? Float.parseFloat(parts[4]) : 0));
            } catch (NumberFormatException notANumber) {
                return Optional.empty();
            }
        }
    }

    private final Plugin plugin;
    private final LogChannel log;
    private final String worldName;
    private final YamlStore builtFile;
    private final Predicate<Player> mayBuild;
    private final Consumer<World> prepare;
    private volatile Location spawn;
    private volatile boolean building;

    /**
     * @param prepare run on the world each time it is made or loaded — gamerules and the like
     */
    public SchematicWorld(Plugin plugin, LogChannel log, String worldName, YamlStore builtFile,
                          Predicate<Player> mayBuild, Consumer<World> prepare) {
        this.plugin = plugin;
        this.log = log;
        this.worldName = worldName;
        this.builtFile = builtFile;
        this.mayBuild = mayBuild;
        this.prepare = prepare;
    }

    /** Where the build lands in the world for a schematic {@code width} × {@code length}. */
    public static Landing absolute(int width, int length, Landing relative) {
        int originX = -width / 2;
        int originZ = -length / 2;
        return new Landing(originX + relative.x(), BASE_Y + relative.y(), originZ + relative.z(), relative.yaw(),
                relative.pitch());
    }

    /** Changes whenever the file does; -2 for none. */
    public static long version(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis() ^ Files.size(file);
        } catch (IOException missing) {
            return -2;
        }
    }

    public String worldName() {
        return worldName;
    }

    public boolean isWorld(World world) {
        return world != null && world.getName().equals(worldName);
    }

    /** Where players land, once the build stands. */
    public Optional<Location> spawn() {
        return Optional.ofNullable(spawn);
    }

    public boolean isBuilding() {
        return building;
    }

    /**
     * Makes sure the build stands: built when the schematic is new or changed (or {@code force}), else only the world
     * loaded. Reads the file and the schematic off the server thread, builds on the regions.
     *
     * @param done told on the global thread where players land, or nothing when there is no build
     */
    public void ensure(Path schematicFile, Landing relative, boolean force, Consumer<Optional<Location>> done) {
        Scheduling.async(plugin, () -> {
            long version = version(schematicFile);
            boolean standing = !force && builtFile.read().getLong("version", -1) == version;
            Schematic schematic;
            try {
                schematic = Schematic.read(schematicFile);
            } catch (IOException unreadable) {
                log.error("{} cannot be read: {}", schematicFile.getFileName(), unreadable.getMessage());
                Scheduling.global(plugin, () -> done.accept(Optional.empty()));
                return;
            }
            Scheduling.global(plugin, () -> {
                World world = world();
                if (world == null) {
                    done.accept(Optional.empty());
                    return;
                }
                Landing at = absolute(schematic.width(), schematic.length(), relative);
                Location landing = new Location(world, at.x(), at.y(), at.z(), at.yaw(), at.pitch());
                if (standing) {
                    stands(world, landing);
                    done.accept(Optional.of(landing));
                    return;
                }
                building = true;
                PasteJob job = new PasteJob(plugin, world, schematic.placements(
                        new Spot(world.getName(), -schematic.width() / 2, BASE_Y, -schematic.length() / 2)));
                log.info("Building {} into '{}': {} chunk(s).", schematicFile.getFileName(), worldName, job.chunks());
                job.start(blocks -> {
                    building = false;
                    stands(world, landing);
                    Scheduling.async(plugin, () -> builtFile.write(yaml -> yaml.set("version", version)));
                    log.info("{} is built into '{}': {} block(s).", schematicFile.getFileName(), worldName, blocks);
                    done.accept(Optional.of(landing));
                });
            });
        });
    }

    private void stands(World world, Location landing) {
        spawn = landing;
        world.setSpawnLocation(landing);
    }

    private World world() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            try {
                world = new WorldCreator(worldName).generator(VoidGenerator.empty()).type(WorldType.FLAT)
                        .generateStructures(false).environment(World.Environment.NORMAL).createWorld();
            } catch (RuntimeException refused) {
                log.error(refused, "The server refused to make the world '{}'.", worldName);
                return null;
            }
        }
        if (world != null) {
            if (!LockedWorlds.isLocked(worldName)) {
                LockedWorlds.lock(plugin, worldName, mayBuild);
            }
            prepare.accept(world);
        }
        return world;
    }

    public void unlock() {
        LockedWorlds.unlock(worldName);
    }
}
