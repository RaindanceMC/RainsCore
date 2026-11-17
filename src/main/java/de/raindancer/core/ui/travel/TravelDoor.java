package de.raindancer.core.ui.travel;

import de.raindancer.core.ui.menu.Menu;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * One way to get somewhere — homes, warps, a random spot, back — as a button on {@code /travel}, offered through
 * {@link TravelDoors}.
 *
 * @param id     {@code plugin:what}
 * @param order  where it stands: lower first (homes 10, warps 20, players 30, random 40, back 50, spawn 60)
 * @param open   opens the plugin's own page, or goes straight there; {@code parent} is where Back leads
 * @param locked why this player may not use it now; empty when they may
 */
public record TravelDoor(String id, int order, String title, String description, BiConsumer<Player, Menu> open,
                         Function<Player, Optional<String>> locked) {

    public TravelDoor {
        if (id == null || !id.matches("[a-z0-9._-]+:[a-z0-9._-]+")) {
            throw new IllegalArgumentException("a travel door's id is plugin:what, not " + id);
        }
        description = description == null ? "" : description;
        locked = locked == null ? player -> Optional.empty() : locked;
    }

    public static TravelDoor of(String id, int order, String title, String description, BiConsumer<Player, Menu> open) {
        return new TravelDoor(id, order, title, description, open, null);
    }

    public TravelDoor lockedWhen(Function<Player, Optional<String>> why) {
        return new TravelDoor(id, order, title, description, open, why);
    }

    /** Locked for whoever lacks {@code permission}, saying so. */
    public TravelDoor needing(String permission) {
        return lockedWhen(player -> player.hasPermission(permission) ? Optional.empty()
                : Optional.of("Not open to you here"));
    }

    Optional<String> lockedFor(Player player) {
        try {
            return locked.apply(player);
        } catch (RuntimeException broken) {
            return Optional.of("Not available right now");
        }
    }
}
