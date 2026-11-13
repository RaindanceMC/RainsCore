package de.raindancer.core.world.movement;

import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Applies {@link MountSettings} to ridden horses: twice a second the horse under every rider gets a speed modifier
 * for the block it stands on. A ridden horse moves on its rider's client, which reads the speed attribute, so the
 * modifier is what the client goes by. Transient — never saved with the horse — and taken off when the rider gets off.
 */
public final class MountSpeeds implements Listener {

    static final NamespacedKey KEY = new NamespacedKey("rainscore", "road_speed");

    private final Plugin plugin;
    private final Supplier<MountSettings> settings;
    private volatile String roadsRead;
    private volatile Set<Material> roads = Set.of();

    public MountSpeeds(Plugin plugin, Supplier<MountSettings> settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public ScheduledTask start() {
        return Scheduling.globalTimer(plugin, 10, 10, task -> {
            MountSettings now = settings.get();
            if (!now.roadBlocks().equals(roadsRead)) {
                roads = RoadSpeed.roads(now.roadBlocks());
                roadsRead = now.roadBlocks();
            }
            for (Player rider : plugin.getServer().getOnlinePlayers()) {
                if (rider.getVehicle() instanceof AbstractHorse) {
                    Scheduling.entity(plugin, rider, () -> apply(rider, now));
                }
            }
        });
    }

    private void apply(Player rider, MountSettings now) {
        Entity vehicle = rider.getVehicle();
        if (!(vehicle instanceof AbstractHorse horse) || horse instanceof Llama) {
            return;
        }
        double factor = 1.0;
        if (!RoadSpeed.isOff(now.roadPercent(), now.offRoadPercent())) {
            Location at = horse.getLocation();
            Material under = horse.getWorld().getBlockAt(at.getBlockX(), RoadSpeed.standingBlockY(at.getY()),
                    at.getBlockZ()).getType();
            factor = RoadSpeed.multiplier(under, roads, now.roadPercent(), now.offRoadPercent());
        }
        set(horse, factor);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player && event.getDismounted() instanceof AbstractHorse horse) {
            set(horse, 1.0);
        }
    }

    static void set(AbstractHorse horse, double factor) {
        AttributeInstance speed = horse.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        double amount = factor - 1.0;
        AttributeModifier current = speed.getModifier(KEY);
        if (current != null && Math.abs(current.getAmount() - amount) < 1e-9) {
            return;
        }
        if (current != null) {
            speed.removeModifier(KEY);
        }
        if (Math.abs(amount) > 1e-9) {
            speed.addTransientModifier(new AttributeModifier(KEY, amount, AttributeModifier.Operation.ADD_SCALAR));
        }
    }
}
