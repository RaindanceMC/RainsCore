package de.raindancer.core.platform.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.function.Consumer;

/**
 * Runs a command for a player as if they had typed it. {@link Player#performCommand} skips
 * {@link PlayerCommandPreprocessEvent}, so every gate built on it (OneBlock's money gate, command blockers) is walked
 * around by any menu button that runs a command — use this instead.
 */
public final class TypedCommands {

    private TypedCommands() {
    }

    /** @return whether it ran; false when a listener cancelled it */
    public static boolean run(Player player, String command) {
        return run(player, command, event -> Bukkit.getPluginManager().callEvent(event));
    }

    static boolean run(Player player, String command, Consumer<PlayerCommandPreprocessEvent> fire) {
        String line = command.startsWith("/") ? command : "/" + command;
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, line);
        fire.accept(event);
        if (event.isCancelled()) {
            return false;
        }
        String typed = event.getMessage();
        player.performCommand(typed.startsWith("/") ? typed.substring(1) : typed);
        return true;
    }
}
