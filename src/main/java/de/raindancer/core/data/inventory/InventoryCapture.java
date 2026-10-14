package de.raindancer.core.data.inventory;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Writes this server's players' inventories into {@link SavedInventories}: when they leave, every few
 * minutes while they play, and for everybody still on when the server stops.
 *
 * <p>Read on the player's own thread — an inventory read from anywhere else can be half of one tick and
 * half of the next — and written off it.
 */
public final class InventoryCapture implements Listener {

    private static final LogChannel log = Log.of("inventory");

    private final Plugin plugin;
    private final SavedInventories store;
    private final Supplier<String> server;
    private final Function<ItemStack[], byte[]> encoder;

    /** @param server this server's id on the network, asked each time (blank on a server of its own) */
    public InventoryCapture(Plugin plugin, SavedInventories store, Supplier<String> server) {
        this(plugin, store, server, ItemStack::serializeItemsAsBytes);
    }

    InventoryCapture(Plugin plugin, SavedInventories store, Supplier<String> server, Function<ItemStack[], byte[]> encoder) {
        this.plugin = plugin;
        this.store = store;
        this.server = server;
        this.encoder = encoder;
    }

    /** One player's inventory as it is now. On the player's thread. */
    SavedInventories.Saved capture(Player player) {
        YamlConfiguration effects = new YamlConfiguration();
        effects.set("effects", new ArrayList<>(player.getActivePotionEffects()));
        return new SavedInventories.Saved(server.get(), player.getUniqueId(), store.now(),
                encoder.apply(player.getInventory().getContents()),
                encoder.apply(player.getEnderChest().getContents()),
                player.getLevel(), player.getExp(), player.getHealth(), player.getFoodLevel(), player.getSaturation(),
                effects.saveToString());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // Already on the player's thread: read now, while the inventory is still theirs; write elsewhere.
        save(event.getPlayer(), true);
    }

    /** Everybody online, each read on their own thread and written off it. For the timer. */
    public void saveEveryone() {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            Scheduling.entity(plugin, online, () -> save(online, true));
        }
    }

    /** Everybody online, read and written now — at shutdown, when nothing can be scheduled any more. */
    public void saveEveryoneNow() {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            save(online, false);
        }
    }

    private void save(Player player, boolean later) {
        SavedInventories.Saved saved;
        try {
            saved = capture(player);
        } catch (RuntimeException failed) {
            log.error(failed, "The inventory of {} could not be read to be kept.", player.getName());
            return;
        }
        Runnable write = () -> {
            if (!store.save(saved)) {
                log.warn("The inventory of {} could not be written to the database this time.", player.getName());
            }
        };
        if (later) {
            Scheduling.async(plugin, write);
        } else {
            write.run();
        }
    }
}
