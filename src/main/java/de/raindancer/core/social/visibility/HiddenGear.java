package de.raindancer.core.social.visibility;

import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hides what a player wears and holds from everybody else — invisibility hides the body, not the armour or the sword.
 * Everybody else is sent empty slots, again every few ticks while it lasts (switching the held item would otherwise
 * show it), and the real gear when it ends. The player themselves sees their own gear throughout.
 */
public final class HiddenGear {

    static final Set<EquipmentSlot> SLOTS = Set.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
    private static final long EVERY_TICKS = 4;

    private static final Map<UUID, ScheduledTask> hidden = new ConcurrentHashMap<>();

    private HiddenGear() {
    }

    /** From now until {@link #show}, or until {@code ticks} have passed. On the player's own thread. */
    public static void hide(Plugin plugin, Player player, long ticks) {
        show(player);
        long until = ticks / EVERY_TICKS;
        int[] sent = {0};
        ScheduledTask task = Scheduling.entityTimer(plugin, player, 1, EVERY_TICKS, running -> {
            if (sent[0]++ > until) {
                running.cancel();
                hidden.remove(player.getUniqueId(), running);
                send(player, real(player));
                return;
            }
            send(player, empty());
        }, () -> hidden.remove(player.getUniqueId()));
        if (task != null) {
            hidden.put(player.getUniqueId(), task);
        }
    }

    public static boolean isHidden(UUID player) {
        return hidden.containsKey(player);
    }

    /** Everybody sees their gear again. On the player's own thread. */
    public static void show(Player player) {
        ScheduledTask task = hidden.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            send(player, real(player));
        }
    }

    /** A player gone: nothing to hide any more. */
    public static void forget(UUID player) {
        ScheduledTask task = hidden.remove(player);
        if (task != null) {
            task.cancel();
        }
    }

    private static Map<EquipmentSlot, ItemStack> empty() {
        Map<EquipmentSlot, ItemStack> slots = new EnumMap<>(EquipmentSlot.class);
        SLOTS.forEach(slot -> slots.put(slot, new ItemStack(Material.AIR)));
        return slots;
    }

    private static Map<EquipmentSlot, ItemStack> real(Player player) {
        Map<EquipmentSlot, ItemStack> slots = new EnumMap<>(EquipmentSlot.class);
        SLOTS.forEach(slot -> slots.put(slot, player.getEquipment().getItem(slot)));
        return slots;
    }

    private static void send(Player player, Map<EquipmentSlot, ItemStack> slots) {
        for (Player viewer : player.getWorld().getPlayers()) {
            if (!viewer.equals(player)) {
                viewer.sendEquipmentChange(player, slots);
            }
        }
    }
}
