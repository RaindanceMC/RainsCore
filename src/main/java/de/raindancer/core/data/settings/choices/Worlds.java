package de.raindancer.core.data.settings.choices;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.List;
import java.util.function.Supplier;

/**
 * The worlds of this server, for a {@code List<String>} setting that names worlds — "worlds without shops",
 * "worlds where this is off": {@code @Choices(Worlds.class)} gives {@code /settings} one button per world instead of
 * a list typed in chat. Asked once, when the schema is built, so a world made later is typed rather than clicked.
 */
public final class Worlds implements Supplier<List<String>> {

    @Override
    public List<String> get() {
        if (Bukkit.getServer() == null) {
            return List.of();
        }
        return Bukkit.getWorlds().stream().map(World::getName).sorted().toList();
    }
}
