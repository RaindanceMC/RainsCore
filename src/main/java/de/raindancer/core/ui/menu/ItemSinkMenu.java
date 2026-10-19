package de.raindancer.core.ui.menu;

import de.raindancer.core.content.items.BoundItems;
import de.raindancer.core.platform.util.Inventories;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.Brand;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A window things are dropped into and are gone — a selling chest, a bin. No button to press: whatever lands in
 * the tray is handed over on the next tick, however it got there (a click, a shift-click, a drag, a number key),
 * which is why it looks every tick rather than at each click. What the taker refuses goes back to the player,
 * never into the tray again. {@link ItemTrayMenu}'s handling of clicks, drags and bound items is kept as it is.
 */
public class ItemSinkMenu extends ItemTrayMenu {

    private final Plugin plugin;
    private final List<String> hint;
    private final Function<List<ItemStack>, List<ItemStack>> swallow;
    private ScheduledTask watching;

    /**
     * @param plugin  whose scheduler looks at the tray; null only in a test, which calls {@link #swallow} itself
     * @param title   MiniMessage
     * @param hint    MiniMessage lines, shown on the button below the tray
     * @param swallow handed what landed, which it now owns; answers what it would not take, given back
     */
    public ItemSinkMenu(Plugin plugin, Player viewer, Brand brand, Menu parent, String title, List<String> hint,
                        Function<List<ItemStack>, List<ItemStack>> swallow) {
        super(viewer, brand, parent, title, "", List.of(), items -> false, ItemTrayMenu.giveBack(viewer));
        this.plugin = plugin;
        this.hint = hint == null ? List.of() : List.copyOf(hint);
        this.swallow = swallow;
    }

    @Override
    protected void render() {
        set(CONFIRM_SLOT, Icons.of(Material.HOPPER, "<white>Drop it in", hint));
        set(TAKE_BACK_SLOT, Icons.of(Material.BARRIER, "<red>Close"), click -> viewer.closeInventory());
    }

    @Override
    public void open() {
        super.open();
        if (watching == null && plugin != null) {
            watching = Scheduling.entityTimer(plugin, viewer, 1L, 1L, task -> swallow());
        }
    }

    /** Hands over everything in the tray now. */
    void swallow() {
        List<ItemStack> landed = new ArrayList<>();
        List<ItemStack> back = new ArrayList<>();
        for (int slot = 0; slot < TRAY_SLOTS; slot++) {
            ItemStack item = getInventory().getItem(slot);
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
                continue;
            }
            getInventory().setItem(slot, null);
            (BoundItems.isBound(item) ? back : landed).add(item);
        }
        if (!landed.isEmpty()) {
            back.addAll(swallow.apply(List.copyOf(landed)));
        }
        if (!back.isEmpty()) {
            Inventories.giveOrDrop(viewer, back);
        }
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        if (watching != null) {
            watching.cancel();
            watching = null;
        }
        swallow();
        super.handleClose(event);
    }
}
