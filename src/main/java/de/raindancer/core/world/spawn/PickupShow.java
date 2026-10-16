package de.raindancer.core.world.spawn;

import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * {@link Pickups} in the world: each available one a turning item over a label, taken by walking into it. The
 * displays are not saved with the chunk, so a crash leaves nothing behind to clean up.
 *
 * <p>Everything touching an entity runs on the region that owns the pickup's spot.
 */
public final class PickupShow {

    /** How close a player has to come, in blocks. */
    public static final double REACH = 1.4;

    private static final long PERIOD_TICKS = 4;

    private final Plugin plugin;
    private final Pickups pickups;
    private final Function<String, ItemStack> look;
    private final Function<String, Component> label;
    private final BiPredicate<Player, Pickups.Pickup> taken;
    private final Map<String, List<UUID>> shown = new ConcurrentHashMap<>();
    private volatile float turn;
    private ScheduledTask timer;

    /**
     * @param look  the item shown for a kind
     * @param label the line over it, for a kind
     * @param taken told on the player's region thread when somebody walks into one; true if it was used — false
     *              leaves it lying (a pickup a full-health player does not need, say)
     */
    public PickupShow(Plugin plugin, Pickups pickups, Function<String, ItemStack> look,
                      Function<String, Component> label, BiPredicate<Player, Pickups.Pickup> taken) {
        this.plugin = plugin;
        this.pickups = pickups;
        this.look = look;
        this.label = label;
        this.taken = taken;
    }

    public void start() {
        timer = Scheduling.globalTimer(plugin, PERIOD_TICKS, PERIOD_TICKS, task -> tick());
    }

    /** Takes every display down; the pickups themselves stay as they are. */
    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        for (Pickups.Pickup pickup : pickups.all()) {
            hide(pickup);
        }
        shown.clear();
    }

    /** Takes down the display of one pickup that is about to be removed or replaced. */
    public void hide(Pickups.Pickup pickup) {
        List<UUID> ids = shown.remove(pickup.id());
        Location at = location(pickup);
        if (ids == null || at == null) {
            return;
        }
        Scheduling.region(plugin, at, () -> ids.forEach(id -> {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }));
    }

    private void tick() {
        turn = (turn + 12f) % 360f;
        for (Pickups.Pickup back : pickups.dueBack()) {
            hide(back);
        }
        for (Pickups.Pickup pickup : pickups.all()) {
            Location at = location(pickup);
            if (at == null) {
                continue;
            }
            Scheduling.region(plugin, at, () -> tend(pickup, at));
        }
    }

    private void tend(Pickups.Pickup pickup, Location at) {
        if (!at.isChunkLoaded()) {
            return;
        }
        if (!pickups.isAvailable(pickup.id())) {
            hide(pickup);
            return;
        }
        List<UUID> ids = shown.get(pickup.id());
        if (ids == null || ids.stream().anyMatch(id -> Bukkit.getEntity(id) == null)) {
            if (ids != null) {
                hide(pickup);
            }
            shown.put(pickup.id(), spawn(pickup, at));
        } else {
            Entity item = Bukkit.getEntity(ids.getFirst());
            if (item != null) {
                item.setRotation(turn, 0);
            }
        }
        for (Player player : at.getNearbyPlayers(REACH + 1)) {
            if (player.isDead() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                    || player.getLocation().distanceSquared(at) > (REACH + 0.6) * (REACH + 0.6)) {
                continue;
            }
            if (pickups.isAvailable(pickup.id()) && taken.test(player, pickup)) {
                pickups.take(pickup.id());
                hide(pickup);
                return;
            }
        }
    }

    private List<UUID> spawn(Pickups.Pickup pickup, Location at) {
        World world = at.getWorld();
        ItemDisplay item = world.spawn(at.clone().add(0, 0.6, 0), ItemDisplay.class, display -> {
            display.setItemStack(look.apply(pickup.kind()));
            display.setBillboard(Display.Billboard.FIXED);
            display.setPersistent(false);
            display.setGlowing(true);
        });
        TextDisplay text = world.spawn(at.clone().add(0, 1.35, 0), TextDisplay.class, display -> {
            display.text(label.apply(pickup.kind()));
            display.setBillboard(Display.Billboard.CENTER);
            display.setPersistent(false);
            display.setShadowed(true);
        });
        return List.of(item.getUniqueId(), text.getUniqueId());
    }

    private static Location location(Pickups.Pickup pickup) {
        World world = Bukkit.getWorld(pickup.world());
        return world == null ? null : new Location(world, pickup.x(), pickup.y(), pickup.z());
    }
}
