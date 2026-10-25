package de.raindancer.core.platform.permission;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Sends a player their commands again whenever {@link Duty} changes for them. The client only offers — and
 * colours as known — the commands it was sent at join, so without this a builder going into admin mode had
 * {@code /gamemode} and {@code /setblock} on the server but "unknown" in their chat box.
 */
public final class DutyCommands {

    private DutyCommands() {
    }

    /**
     * @param online        the player by id, or null when they are not here
     * @param everybody     everyone online, for a change that is the whole server's (a null id)
     * @param onTheirThread runs a task on the player's own thread — after LuckPerms has seen the new contexts
     */
    public static Consumer<UUID> refreshing(Function<UUID, ? extends Player> online,
                                            Supplier<? extends Collection<? extends Player>> everybody,
                                            BiConsumer<Player, Runnable> onTheirThread) {
        return who -> {
            if (who == null) {
                everybody.get().forEach(player -> onTheirThread.accept(player, player::updateCommands));
                return;
            }
            Player player = online.apply(who);
            if (player != null) {
                onTheirThread.accept(player, player::updateCommands);
            }
        };
    }
}
