package de.raindancer.core.ui.menu;

import de.raindancer.core.content.items.BoundItems;
import de.raindancer.core.platform.util.Inventories;
import de.raindancer.core.ui.chat.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A window to put items into, and a button to say "that's what I mean" — for a parcel, a trade offer, a
 * deposit.
 *
 * <h2>Why this exists</h2>
 * Every menu here is a set of buttons, and {@link MenuListener} cancels anything that would move an item
 * into one. A screen that is <em>for</em> moving items had to opt out of that and then handle shift-clicks,
 * drags, double clicks and closing by itself, and each of the old copies got one of them wrong — which in a
 * window holding somebody's items is the difference between a bug and a duplication.
 *
 * <h2>What it promises</h2>
 * <ul>
 *   <li>The top three rows are the tray; everything else is a button and cannot be dragged or clicked into.</li>
 *   <li>Shift-click from the player's inventory fills the tray (merging stacks); what does not fit stays put.</li>
 *   <li>Items leave the tray exactly one way: handed to {@code onConfirm} (the tray is emptied first), or handed
 *       to {@code onAbandon} when the window closes for any other reason. Never both, never neither.</li>
 *   <li>{@code onConfirm} may say no — "put something in first" — and then nothing moves.</li>
 *   <li>A click another handler already cancelled stays cancelled. Core's {@code BoundItemListener} cancels a
 *       bound item's shift-click into a container, and {@link MenuListener} still hears the event; moving the
 *       item by hand anyway would be exactly the bypass that listener exists to stop. Bound items are also
 *       refused outright, so a stack that reached the tray another way is not carried on.</li>
 * </ul>
 *
 * <p>Not redrawn: {@link #refresh()} does nothing, because a redraw clears the window and the tray is the
 * player's items, not something this class can paint again.
 */
public class ItemTrayMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    public static final int ROWS = 5;
    public static final int SIZE = ROWS * 9;
    public static final int TRAY_SLOTS = 27;
    public static final int HINT_SLOT = 29;
    public static final int CONFIRM_SLOT = 31;
    public static final int TAKE_BACK_SLOT = 33;

    private final String title;
    private final String confirmLabel;
    private final List<String> confirmLore;
    private final Predicate<List<ItemStack>> onConfirm;
    private final Consumer<List<ItemStack>> onAbandon;
    private boolean settled;
    private boolean opened;

    /**
     * @param title        MiniMessage
     * @param confirmLabel MiniMessage, the button's name — "Send"
     * @param onConfirm    handed the tray's contents, which it now owns; returns false to refuse, leaving the
     *                     items in the tray. On true the window closes. Also asked when the tray is empty, with an
     *                     empty list, so the caller can say "put something in first" — a button that does nothing
     *                     is one that gets pressed four more times
     * @param onAbandon    handed what was in the tray when the window closed without confirming. Use
     *                     {@link #giveBack} unless the items belong somewhere else
     */
    public ItemTrayMenu(Player viewer, Brand brand, Menu parent, String title, String confirmLabel,
                        List<String> confirmLore, Predicate<List<ItemStack>> onConfirm,
                        Consumer<List<ItemStack>> onAbandon) {
        super(viewer, brand, parent, ROWS);
        this.title = title;
        this.confirmLabel = confirmLabel;
        this.confirmLore = confirmLore == null ? List.of() : List.copyOf(confirmLore);
        this.onConfirm = onConfirm;
        this.onAbandon = onAbandon;
    }

    /** The usual {@code onAbandon}: back into the inventory, the rest dropped at their feet. */
    public static Consumer<List<ItemStack>> giveBack(Player player) {
        return items -> Inventories.giveOrDrop(player, items);
    }

    @Override
    protected Component title() {
        return MINI.deserialize(title);
    }

    @Override
    public String breadcrumb() {
        return "the tray";
    }

    @Override
    protected void render() {
        set(HINT_SLOT, Icons.of(Material.HOPPER, "<white>Put your items in",
                "<gray>Click them into the top rows, or",
                "<gray>shift-click them from your inventory."));
        List<String> lore = new ArrayList<>(confirmLore);
        set(CONFIRM_SLOT, Icons.of(Material.LIME_CONCRETE, confirmLabel, lore), this::confirm);
        set(TAKE_BACK_SLOT, Icons.of(Material.BARRIER, "<red>Take everything back",
                "<gray>Closes this window and returns your items."),
                click -> viewer.closeInventory());
    }

    /** Only what the tray does not cover: the tray's own slots stay empty, so they are not filled with panes. */
    @Override
    protected void decorate() {
        ItemStack frame = Icons.filler(Material.GRAY_STAINED_GLASS_PANE);
        for (int slot = TRAY_SLOTS; slot < SIZE; slot++) {
            if (getInventory().getItem(slot) == null) {
                getInventory().setItem(slot, frame);
            }
        }
    }

    @Override
    public void refresh() {
        // See the class comment.
    }

    /**
     * Opens the window once; asked again it shows the same window, with the tray as it is.
     *
     * <p>Menu's own {@code open} repaints, which clears the inventory — the tray's contents with it.
     */
    @Override
    public void open() {
        if (opened) {
            viewer.openInventory(getInventory());
            return;
        }
        opened = true;
        super.open();
    }

    @Override
    public void reopen() {
        // A new inventory would leave the items in the old one, which nobody holds any more.
        open();
    }

    @Override
    public boolean allowBottomInventoryInteraction() {
        return true;
    }

    @Override
    public boolean acceptsItemsIn(int rawSlot) {
        return rawSlot >= 0 && rawSlot < TRAY_SLOTS;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (acceptsItemsIn(slot)) {
            if (event.isCancelled()) {
                return;
            }
            // Left to the game, bar the one action that gathers matching items from the whole window.
            if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
            }
            return;
        }
        super.handleClick(event);
    }

    @Override
    public void handleBottomClick(InventoryClickEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }
        if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
            event.setCancelled(true);
            moveIntoTray(event);
            return;
        }
        // Moving things about in their own inventory is theirs to do.
    }

    private void moveIntoTray(InventoryClickEvent event) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || clicked.getAmount() <= 0
                || BoundItems.isBound(clicked)) {
            return;
        }
        int max = Math.max(1, clicked.getMaxStackSize());
        int left = clicked.getAmount();
        for (int slot = 0; slot < TRAY_SLOTS && left > 0; slot++) {
            ItemStack there = getInventory().getItem(slot);
            if (there != null && !there.getType().isAir() && there.getAmount() < max && there.isSimilar(clicked)) {
                int moved = Math.min(left, max - there.getAmount());
                there.setAmount(there.getAmount() + moved);
                getInventory().setItem(slot, there);
                left -= moved;
            }
        }
        for (int slot = 0; slot < TRAY_SLOTS && left > 0; slot++) {
            ItemStack there = getInventory().getItem(slot);
            if (there == null || there.getType().isAir()) {
                ItemStack part = clicked.clone();
                part.setAmount(Math.min(left, max));
                getInventory().setItem(slot, part);
                left -= part.getAmount();
            }
        }
        if (left == clicked.getAmount()) {
            return;
        }
        if (left <= 0) {
            event.setCurrentItem(null);
        } else {
            clicked.setAmount(left);
            event.setCurrentItem(clicked);
        }
    }

    private void confirm(InventoryClickEvent click) {
        returnBound();
        ItemStack[] layout = snapshot();
        List<ItemStack> items = takeTray();
        boolean accepted;
        try {
            accepted = onConfirm.test(items);
        } catch (RuntimeException failed) {
            putBack(layout);
            throw failed;
        }
        if (!accepted) {
            putBack(layout);
            return;
        }
        settled = true;
        viewer.closeInventory();
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        if (settled) {
            return;
        }
        settled = true;
        returnBound();
        List<ItemStack> items = takeTray();
        if (!items.isEmpty()) {
            onAbandon.accept(items);
        }
    }

    /** Empties the tray and answers what was in it. */
    private List<ItemStack> takeTray() {
        List<ItemStack> items = new ArrayList<>();
        for (int slot = 0; slot < TRAY_SLOTS; slot++) {
            ItemStack item = getInventory().getItem(slot);
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                items.add(item);
            }
            getInventory().setItem(slot, null);
        }
        return items;
    }

    /**
     * Gives bound items straight back to the viewer and clears their slots.
     *
     * <p>Done first, before the tray is snapshotted: a snapshot taken afterwards cannot put them back in the tray
     * when a confirmation is refused, which would leave the same stack in the tray and in the inventory.
     */
    private void returnBound() {
        List<ItemStack> bound = new ArrayList<>();
        for (int slot = 0; slot < TRAY_SLOTS; slot++) {
            ItemStack item = getInventory().getItem(slot);
            if (item != null && BoundItems.isBound(item)) {
                bound.add(item);
                getInventory().setItem(slot, null);
            }
        }
        if (!bound.isEmpty()) {
            Inventories.giveOrDrop(viewer, bound);
        }
    }

    private ItemStack[] snapshot() {
        ItemStack[] layout = new ItemStack[TRAY_SLOTS];
        for (int slot = 0; slot < TRAY_SLOTS; slot++) {
            layout[slot] = getInventory().getItem(slot);
        }
        return layout;
    }

    /** Where each item was, so a refused confirmation changes nothing the player can see. */
    private void putBack(ItemStack[] layout) {
        for (int slot = 0; slot < TRAY_SLOTS; slot++) {
            getInventory().setItem(slot, layout[slot]);
        }
    }
}
