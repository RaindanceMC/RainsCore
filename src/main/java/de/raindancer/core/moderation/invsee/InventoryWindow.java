package de.raindancer.core.moderation.invsee;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.platform.util.Scheduling;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One moderator's window onto one player's inventory.
 *
 * <h2>Why this is not a {@code Menu}</h2>
 * Every other screen in these plugins is a set of buttons, and the framework for those cancels every
 * click by design — which is exactly right for a menu and exactly wrong here, where the point is to
 * be able to take a stolen item out. This is the one window in the codebase where a click may move a
 * real item, so it is written out rather than fitted to a framework whose first rule it has to
 * break.
 *
 * <h2>How a change reaches the player</h2>
 * Two different ways, because the two cases fail differently:
 * <ul>
 *   <li><b>Online:</b> each change is pushed straight onto the live inventory. Writing the whole
 *       snapshot back instead would undo anything its owner picked up while the moderator was
 *       looking.</li>
 *   <li><b>Offline:</b> changes go into the snapshot, and the file is written once, when the window
 *       closes. One write instead of one per click, and the player is held out of the server for
 *       the whole of it — see {@link OfflineEdits}.</li>
 * </ul>
 *
 * <p>The window is read after the click rather than the click being interpreted. Minecraft has
 * roughly a dozen ways to move an item — shift-click, number keys, double-click gathering, drags
 * that cross both inventories — and a handler that works out what each of them meant is a handler
 * that gets one of them wrong. What actually ended up in the window is not ambiguous.
 */
public final class InventoryWindow implements InventoryHolder {

    private static final LogChannel log = Log.of("invsee");

    private final Plugin plugin;
    private final Player watcher;
    private final UUID owner;
    private final String ownerName;
    private final Access access;
    /** Whether the owner is on the server, and therefore whether changes go out immediately. */
    private final boolean live;
    private final InventorySource source;

    /** What the window is showing, and for an offline player what will be written. */
    private Carried<ItemStack> carried;
    /** What was read out of the owner when the window opened — the line between theirs and added. */
    private final Carried<ItemStack> asFound;
    /** The ender chest is a second page of the same window. */
    private boolean showingEnderChest;
    private Inventory inventory;
    /** Set while this class is the one writing, so its own writes do not read as edits. */
    private boolean painting;
    /** Where each change is written down. Null when this server keeps no record. */
    private Audit audit;
    /** Where the wording comes from. Null on a server with no message file. */
    private Messages words;
    /** Whether the ender chest is offered at all. On by default — see {@link #enderChest(boolean)}. */
    private boolean enderChestAllowed = true;

    public InventoryWindow(Plugin plugin, Player watcher, UUID owner, String ownerName,
                           Access access, boolean live, InventorySource source,
                           Carried<ItemStack> carried) {
        this.plugin = plugin;
        this.watcher = watcher;
        this.owner = owner;
        this.ownerName = ownerName;
        this.access = access;
        this.live = live;
        this.source = source;
        this.carried = carried;
        this.asFound = carried;
    }

    // ------------------------------------------------------------------------------ opening

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, Layout.SIZE, title());
        }
        return inventory;
    }

    private Component title() {
        if (words != null) {
            return words.get("invsee.title", "who", ownerName, "access", access.saying(words));
        }
        return Component.text(ownerName + ": " + access.saying())
                .color(access.canEdit() ? NamedTextColor.GOLD : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    public void open() {
        paint();
        watcher.openInventory(getInventory());
    }

    /** Tells this where to write down what the moderator changes. */
    public void audit(Audit audit) {
        this.audit = audit;
    }

    /** Tells this where its wording comes from. */
    public void messages(Messages words) {
        this.words = words;
    }

    /** Tells this whether the ender chest is offered at all — set before the first {@link #paint()}. */
    public void enderChest(boolean allowed) {
        this.enderChestAllowed = allowed;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public Player watcher() {
        return watcher;
    }

    public Access access() {
        return access;
    }

    public boolean isLive() {
        return live;
    }

    /** What the window now says they are carrying — what an offline write puts in the file. */
    public Carried<ItemStack> carried() {
        return carried;
    }

    /**
     * Gives back whatever the moderator put into a window whose changes are not being written.
     *
     * <p>Closing a window destroys what is in it. That is fine when the change was written, because
     * the item is now the owner's; it is item deletion when the change was dropped — an offline edit
     * superseded by its owner logging in, or a write that failed. What they added has to come back
     * to them.
     *
     * <p>What was already the owner's is not given away: only the difference between what the window
     * holds now and what was read out of the owner in the first place.
     *
     * @return how many stacks were handed back
     */
    public int giveBackAdditions() {
        int given = 0;
        for (Section section : Section.values()) {
            for (int within = 0; within < section.size(); within++) {
                ItemStack now = carried.at(section, within);
                if (now == null || now.equals(asFound.at(section, within))) {
                    continue;
                }
                for (ItemStack over : watcher.getInventory().addItem(now.clone()).values()) {
                    // Their inventory is full. On the floor where they are standing is still better
                    // than gone.
                    watcher.getWorld().dropItemNaturally(watcher.getLocation(), over);
                }
                given++;
            }
        }
        if (given > 0) {
            log.info("Gave {} stack(s) back to {}: their changes to {} were not written.",
                    given, watcher.getName(), ownerName);
        }
        return given;
    }

    /**
     * Undoes, on the moderator's side, an edit that was not written: gives back what they added
     * ({@link #giveBackAdditions}) and then takes back what they took out. The file still holds what
     * they took, so leaving it with them as well is a copy of every item they moved out.
     *
     * <p>Giving back comes first: a stack the moderator only partly emptied gives the rest back, and
     * the whole original is then taken, which leaves them with exactly what they had before.
     *
     * @return how many stacks could not be taken back — moved somewhere this cannot reach, dropped
     */
    public int undoUnwritten() {
        giveBackAdditions();
        int missing = 0;
        for (Section section : Section.values()) {
            for (int within = 0; within < section.size(); within++) {
                ItemStack was = asFound.at(section, within);
                if (was == null || was.equals(carried.at(section, within))) {
                    continue;
                }
                if (!watcher.getInventory().removeItem(was.clone()).isEmpty()) {
                    missing++;
                }
            }
        }
        if (missing > 0) {
            log.warn("{} kept {} stack(s) taken out of {}'s saved inventory although the change was "
                    + "not written. They are no longer in the moderator's inventory to take back.",
                    watcher.getName(), missing, ownerName);
        }
        return missing;
    }

    // ----------------------------------------------------------------------------- painting

    /** Draws the page the window is on. */
    public void paint() {
        painting = true;
        try {
            Inventory window = getInventory();
            window.clear();
            if (showingEnderChest) {
                paintEnderChest(window);
            } else {
                paintCarried(window);
            }
        } finally {
            painting = false;
        }
    }

    private void paintCarried(Inventory window) {
        for (Section section : List.of(Section.STORAGE, Section.HOTBAR, Section.ARMOUR,
                Section.OFF_HAND)) {
            for (int within = 0; within < section.size(); within++) {
                window.setItem(Layout.slotFor(section, within), carried.at(section, within));
            }
        }
        for (int slot : Layout.chromeSlots()) {
            window.setItem(slot, divider());
        }
        // Empty armour and off-hand slots are left genuinely empty rather than labelled with a
        // pane. A label there would be indistinguishable from a moderator putting a pane in that
        // slot, and telling those two apart by looking at the item is a guess — one that eats a
        // real item the first time somebody equips somebody with stained glass.
        window.setItem(Layout.ENDER_CHEST, enderChestAllowed
                ? button(Material.ENDER_CHEST,
                        say("invsee.ender-chest-button.name", "Ender Chest"),
                        say("invsee.ender-chest-button.lore",
                                "Their ender chest: " + carried.countIn(Section.ENDER_CHEST)
                                        + " of 27 used",
                                "used", carried.countIn(Section.ENDER_CHEST),
                                "total", Section.ENDER_CHEST.size()),
                        say("invsee.ender-chest-button.hint", "Click to look inside"))
                : divider());
    }

    private void paintEnderChest(Inventory window) {
        for (int within = 0; within < Section.ENDER_CHEST.size(); within++) {
            window.setItem(within, carried.at(Section.ENDER_CHEST, within));
        }
        for (int slot = Section.ENDER_CHEST.size(); slot < Layout.SIZE; slot++) {
            window.setItem(slot, divider());
        }
        window.setItem(Layout.ARMOUR_FIRST, button(Material.CHEST,
                say("invsee.back-button.name", "Back"),
                say("invsee.back-button.lore", "What " + ownerName + " is carrying",
                        "who", ownerName),
                say("invsee.back-button.hint", "Click to go back")));
        window.setItem(Layout.ENDER_CHEST, button(Material.ENDER_CHEST,
                say("invsee.section.ender-chest", "Ender Chest"), ""));
    }

    /**
     * One line of text, in this server's words or in the built-in ones.
     *
     * <p>Plain text rather than a component, because these are an item's name and lore and the caller
     * turns them into components with its own colours. A message file that puts markup in them would
     * therefore be shown literally, which is why these keys are worded without any.
     */
    private String say(String key, String builtIn, Object... values) {
        if (words == null) {
            return builtIn;
        }
        return PlainTextComponentSerializer.plainText()
                .serialize(words.get(key, values));
    }

    private static ItemStack divider() {
        return placeholder(Material.GRAY_STAINED_GLASS_PANE, " ");
    }

    private static ItemStack placeholder(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            if (!line.isEmpty()) {
                lines.add(Component.text(line).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
        }
        meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------------------------ clicking

    /**
     * Whether a click on one of the window's own slots may go ahead.
     *
     * @return true when the click should be cancelled
     */
    public boolean shouldCancel(int windowSlot) {
        if (painting) {
            return true;
        }
        if (windowSlot < 0) {
            // Outside the window entirely — dropping what is on the cursor. That item is the
            // moderator's own: everything they could have picked up out of the window, they were
            // allowed to pick up. Cancelling here would leave them unable to put down something
            // they are holding, with a full inventory and no way out but to close the window.
            return false;
        }
        Optional<Layout.Placed> placed = placedAt(windowSlot);
        if (placed.isEmpty()) {
            // Chrome, a gap, or the divider. A click here means nothing and must do nothing rather
            // than being rounded to the nearest real slot.
            return true;
        }
        Section section = placed.get().section();
        if (!showingEnderChest && section == Section.ENDER_CHEST) {
            // The button, not a slot.
            return true;
        }
        return !access.mayChange(section);
    }

    /** What a window slot is showing on the page it is on. */
    public Optional<Layout.Placed> placedAt(int windowSlot) {
        if (showingEnderChest) {
            if (windowSlot >= 0 && windowSlot < Section.ENDER_CHEST.size()) {
                return Optional.of(new Layout.Placed(Section.ENDER_CHEST, windowSlot));
            }
            return Optional.empty();
        }
        return Layout.at(windowSlot);
    }

    /** Whether this click was on one of the two page buttons, and turns the page rather than moving
     * an item. */
    public boolean isPageButton(int windowSlot) {
        if (showingEnderChest) {
            return windowSlot == Layout.ARMOUR_FIRST || windowSlot == Layout.ENDER_CHEST;
        }
        return windowSlot == Layout.ENDER_CHEST;
    }

    /** Turns the page. */
    public void turnPage(int windowSlot) {
        boolean turning = (showingEnderChest && windowSlot == Layout.ARMOUR_FIRST)
                || (!showingEnderChest && windowSlot == Layout.ENDER_CHEST);
        if (!turning || (!showingEnderChest && !enderChestAllowed)) {
            // The second half only matters if something else still calls this directly — paintCarried
            // no longer draws a clickable button there once the ender chest is switched off, so a
            // normal click can no longer reach this in the first place.
            return;
        }
        // Before the page changes, not after, and not on the scheduled sync a tick later.
        //
        // Turning the page repaints the whole window. Anything the moderator moved into it and had
        // not been taken yet would be painted over and simply gone — and the sync that was already
        // scheduled would then run against the *new* page and find nothing wrong with it. That is a
        // real item deleted by two clicks in the same tick, with nobody at fault and nothing in the
        // log.
        sync();
        showingEnderChest = !showingEnderChest;
        paint();
    }

    public boolean isShowingEnderChest() {
        return showingEnderChest;
    }

    /**
     * Reads the window back after a click and takes whatever changed with it.
     *
     * <p>Run a tick later, because during the event the window still holds what it held before. On
     * Folia this is scheduled against the moderator, which is the region that owns both the window
     * and — when they are online — usually the player being looked at.
     */
    public void syncSoon() {
        Scheduling.entityLater(plugin, watcher, 1L, this::sync);
    }

    /** Takes what the window now shows and puts it where it belongs. */
    public void sync() {
        if (inventory == null || painting) {
            return;
        }
        int changed = 0;
        boolean protectedSlotTouched = false;
        for (Section section : Section.values()) {
            if (showingEnderChest != (section == Section.ENDER_CHEST)) {
                // Only the page being looked at can have changed.
                continue;
            }
            for (int within = 0; within < section.size(); within++) {
                int slot = showingEnderChest ? within : Layout.slotFor(section, within);
                ItemStack shown = real(inventory.getItem(slot));
                ItemStack before = carried.at(section, within);
                if (same(shown, before)) {
                    continue;
                }
                if (!access.mayChange(section)) {
                    // Should not be reachable — every click on a protected slot is cancelled — but
                    // the gestures that reach a slot without ever naming it (a double-click
                    // gathering matching items from the top inventory, most of all) have a way of
                    // finding the one that was not thought of. That slot is put back and the rest
                    // of the loop carries on.
                    //
                    // Carrying on is the point. Abandoning the loop here would throw away every
                    // change in a slot this had not looked at yet — a moderator's five legitimate
                    // edits lost because the sixth touched the armour.
                    protectedSlotTouched = true;
                    continue;
                }
                carried = carried.with(section, within, shown);
                changed++;
                if (live) {
                    pushLive(section, within, shown);
                }
                if (audit != null) {
                    // One entry per slot rather than one per window. "Took a diamond sword out of
                    // slot 4" is the sentence somebody needs a year later; "changed 3 slots" is not,
                    // and the fields are what make it searchable at all.
                    audit.record(AuditEntry.of("invsee", changeAction(before, shown))
                            .by(watcher.getUniqueId(), watcher.getName())
                            .to(owner, ownerName)
                            .saying(describe(before, shown))
                            .with("section", section.name())
                            .with("slot", within)
                            .with("was", describeItem(before))
                            .with("now", describeItem(shown))
                            .with("source", live ? "live" : "playerdata"));
                }
            }
        }
        if (protectedSlotTouched) {
            log.warn("A change reached a protected part of {}'s inventory and was put back.",
                    ownerName);
            paint();
        }
        if (changed > 0) {
            log.debug("{} changed {} slot(s) of {}'s inventory.", watcher.getName(), changed,
                    ownerName);
        }
    }

    /**
     * One change onto the owner's live inventory, made on the thread that owns them. That is this
     * thread whenever both stand in one region — always, on Paper — and otherwise the owner's own
     * scheduler: on Folia, writing into a player another region is ticking races the game's own
     * changes to the same slots.
     */
    private void pushLive(Section section, int within, ItemStack shown) {
        Player ownerPlayer = Bukkit.getPlayer(owner);
        if (ownerPlayer == null || Bukkit.isOwnedByCurrentRegion(ownerPlayer)) {
            source.set(owner, section, within, shown);
            return;
        }
        // A copy: what the window hands out can be a live view of its slot, and by the time this runs
        // the moderator may have moved on with it.
        ItemStack copy = shown == null ? null : shown.clone();
        Scheduling.entity(plugin, ownerPlayer, () -> source.set(owner, section, within, copy));
    }

    /**
     * An empty slot is nothing.
     *
     * <p>Deliberately the only rule: every slot this reads is one that only ever holds real items,
     * because the chrome lives in slots {@link Layout} calls chrome and nothing else is painted into
     * an item slot. A rule that tried to recognise decoration by what it looks like would take a
     * moderator's own stained glass off them.
     */
    private static ItemStack real(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item;
    }

    /** What kind of change this was, so the journal can be searched by it. */
    private static String changeAction(ItemStack before, ItemStack after) {
        if (before == null) {
            return "put an item in";
        }
        if (after == null) {
            return "took an item out";
        }
        return "replaced an item";
    }

    private static String describe(ItemStack before, ItemStack after) {
        if (before == null) {
            return describeItem(after);
        }
        if (after == null) {
            return describeItem(before);
        }
        return describeItem(before) + " became " + describeItem(after);
    }

    /**
     * One item in a few words.
     *
     * <p>The type and the count, and the name when somebody gave it one — enough to recognise the
     * item in a report without the journal becoming a second copy of everybody's inventory.
     */
    private static String describeItem(ItemStack item) {
        if (item == null) {
            return "nothing";
        }
        String said = item.getAmount() + "x " + item.getType().name().toLowerCase();
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            said += " named \"" + PlainTextComponentSerializer
                    .plainText().serialize(meta.displayName()) + "\"";
        }
        return said;
    }

    private static boolean same(ItemStack one, ItemStack other) {
        return one == null ? other == null : one.equals(other);
    }
}
