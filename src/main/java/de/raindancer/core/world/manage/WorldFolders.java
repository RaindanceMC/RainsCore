package de.raindancer.core.world.manage;

import org.bukkit.Server;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether a world was ever made on this server, before loading it. Paper 26 keeps worlds made at runtime under
 * {@code <level-name>/dimensions/minecraft/<name>}, older servers beside the main level — a check for only the
 * old folder never finds the world again after a restart.
 */
public final class WorldFolders {

    private WorldFolders() {
    }

    public static boolean exists(Server server, String name) {
        return exists(server.getWorldContainer().toPath(), server.getWorlds().isEmpty() ? "world"
                : server.getWorlds().getFirst().getName(), name);
    }

    static boolean exists(Path container, String levelName, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return Files.isDirectory(container.resolve(levelName).resolve("dimensions").resolve("minecraft").resolve(name))
                || Files.isDirectory(container.resolve(name));
    }
}
