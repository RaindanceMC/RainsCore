package de.raindancer.core.ui.menu;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The base for every screen in every one of these plugins.
 *
 * <h2>Why there is only one of these now</h2>
 * There were five: the claims module's, the core's, the ghast lines', homes' and the resourcepack
 * manager's. Each had its own idea of what a window title was, where Back went, whether the bottom
 * row was safe to write to, and what a filler pane looked like — so the same plugin looked like five
 * plugins, and a fix to one of them stayed in that one. This is the claims framework, which was the
 * most evolved of the five, with its arithmetic pulled out into {@link MenuLayout} so it can be
 * tested without a server.
 *
 * <h2>The grammar</h2>
 * See {@link MenuLayout} for the grid. Three rules make it hold:
 * <ul>
 *   <li><b>The chrome row is not a page's business.</b> {@link #set} refuses it outright and the
 *       framework paints it after {@link #render()} returns. The old framework had the navigation
 *       as a method a subclass called at the end, which meant a page could quietly overwrite its own
 *       Back button — and a paged list did exactly that to its own page arrows.</li>
 *   <li><b>A band is an order, not a slot.</b> {@link #band} takes a column meaning "this comes
 *       third", and the band is centred once {@code render()} has decided which of its buttons this
 *       viewer actually gets to see.</li>
 *   <li><b>A button a viewer may not use is shown, greyed, with the reason</b> — see the four-argument
 *       {@link #band}. A live-looking button that answers a click with an error is worse.</li>
 * </ul>
 *
 * <h2>Identity</h2>
 * Being the {@link InventoryHolder} is what lets {@link MenuListener} recognise our own inventories
 * without keeping a registry of open views — and therefore without holding {@link Player} references
 * that would pin a whole world in the heap.
 */
public abstract class Menu implements InventoryHolder {

    private static final LogChannel log = Log.of("gui");

    protected final Player viewer;
    private final Brand brand;
    private final MenuLayout layout;
    private Menu parent;

    private Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();
    /** Set while the framework writes the chrome, so {@link #set} lets those slots through. */
    private boolean writingChrome;

    /** What {@link #render()} asked for, per band, in column order — laid out once it has finished. */
    private final Map<Integer, NavigableMap<Integer, Placement>> pendingBands = new HashMap<>();
    private final Map<Integer, NavigableMap<Integer, Placement>> pendingCells = new HashMap<>();
    private final NavigableMap<Integer, Placement> pendingToolbar = new TreeMap<>();
    private Placement pendingDanger;

    /** An icon and what a click on it does, waiting for its slot. */
    private record Placement(ItemStack item, Consumer<InventoryClickEvent> handler) {
    }

    protected Menu(Player viewer, Brand brand, Menu parent) {
        this(viewer, brand, parent, MenuLayout.PAGE_ROWS);
    }

    /** @param rows six for a page; the dialogs use three and get Back and Close only */
    protected Menu(Player viewer, Brand brand, Menu parent, int rows) {
        this.viewer = viewer;
        this.brand = brand;
        this.parent = parent;
        this.layout = new MenuLayout(rows);
    }

    /**
     * What this page is. The plugin's name is put in front by the framework, so every window in
     * every plugin reads the same way and only the part after the dash differs.
     */
    protected abstract Component title();

    /** Fills the content area. The header, the frame and the chrome are not this method's business. */
    protected abstract void render();

    public MenuLayout layout() {
        return layout;
    }

    protected Brand brand() {
        return brand;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, layout.size(), shownTitle());
        }
        return inventory;
    }

    public void open() {
        Inventory target = getInventory();
        paint();
        viewer.openInventory(target);
    }

    /**
     * Back to the page that opened this one, or out of the menus entirely when there is none.
     *
     * <h2>Why this is a method rather than two lines at each call site</h2>
     * Because it was two lines at each call site, and five of six choosers did not write them. Picking a
     * player, an item, a sound, an effect or a mob answered the caller's callback and then either closed the
     * window or left the list on screen — so the page that wanted the answer was never seen again. A server
     * owner reported it as "the screens do not close on a click and do not go back".
     *
     * <p>The plugins had been papering over it: a callback ending in {@code refresh()}, which redraws a menu
     * that is not the one being looked at, and every caller had to know to do it. That is a convention, and
     * five of six proved a convention does not hold. This is the mechanism.
     *
     * <p>Closing the inventory is the honest fallback when nothing opened this — a command can open a chooser
     * with no parent, and there is nowhere to go back to.
     */
    protected void backToWhoeverOpenedThis() {
        if (!stillOnScreen()) {
            // The answer already moved them on — a player picked from a list opens that player's page.
            // Going back now would close the very page the answer opened.
            return;
        }
        Menu opener = parent();
        if (opener != null) {
            opener.open();
        } else {
            viewer.closeInventory();
        }
    }

    private boolean stillOnScreen() {
        org.bukkit.inventory.InventoryView open = viewer.getOpenInventory();
        return open != null && open.getTopInventory() != null && open.getTopInventory().getHolder(false) == this;
    }

    /** Rebuilds the contents in place; the view stays open, which is what a toggle needs. */
    public void refresh() {
        if (inventory == null) {
            open();
            return;
        }
        paint();
    }

    /**
     * Re-opens with a fresh inventory. Needed when the title changes, because Minecraft cannot
     * retitle an open container.
     */
    public void reopen() {
        inventory = null;
        open();
    }

    /** Content, then layout, then decoration, then chrome — in that order, always. */
    private void paint() {
        handlers.clear();
        pendingBands.clear();
        pendingCells.clear();
        pendingToolbar.clear();
        pendingDanger = null;
        getInventory().clear();
        try {
            render();
        } catch (RuntimeException failure) {
            // A page that cannot draw itself must not leave the player looking at a frozen window
            // with no way out: the chrome below still paints, so Back and Close work.
            log.error(failure, "The {} page could not be drawn.", getClass().getSimpleName());
        }
        layoutBands();
        decorate();
        // Again, because band(), toolbar() and cell() buffer rather than place, and decorate() is the obvious
        // place to put chrome that does not depend on the page's contents. Flushed only once, before decorate,
        // anything asked for there went into the buffer and was silently never drawn — no error, no log line,
        // just a missing button. The flush empties the buffer as it writes, so nothing lands twice.
        layoutBands();
        paintChrome();
        if (designed()) {
            veil();
        }
    }

    /** The decorative panes give way to the painting: they become slots that draw nothing. */
    private void veil() {
        ItemStack blank = null;
        for (int slot = 0; slot < layout.size(); slot++) {
            if (Icons.isFiller(getInventory().getItem(slot))) {
                if (blank == null) {
                    blank = MenuStyle.blank();
                }
                getInventory().setItem(slot, blank);
            }
        }
    }

    /**
     * The painting behind this page for a viewer wearing the pack; {@code null} keeps the classic window. Staff
     * screens answer {@link Backdrop#workshop}, a plugin with a window of its own its own backdrop.
     *
     * <p>A backdrop of a plugin's own has to keep the slots the page puts things in where its painting has them.
     */
    protected Backdrop backdrop() {
        return Backdrop.house(layout.rows());
    }

    /** Whether this viewer sees the painted window. */
    protected boolean designed() {
        return viewer != null && MenuStyle.wearing(viewer.getUniqueId()) && backdrop() != null;
    }

    /** The title as the window shows it: {@link #windowTitle()}, behind the painting when there is one. */
    public Component shownTitle() {
        Component title = windowTitle();
        if (!designed()) {
            return title;
        }
        // The trail was fitted to the game's font; the title font is wider, so it is measured again in that.
        // The brand shortens a page with "…" to fit the game's font; in the wider title font the page alone, whole.
        if (Backdrop.TITLE_FONT.equals(backdrop().titleFont())) {
            String plain = PLAIN.serialize(title);
            if (plain.contains("\u2026") || TitleFont.width(plain) > TitleFont.BUDGET) {
                Component page = brand.trail(null, title());
                String pagePlain = PLAIN.serialize(page);
                title = !pagePlain.contains("\u2026") && TitleFont.width(pagePlain) <= TitleFont.BUDGET ? page : title();
            }
        }
        return backdrop().frame(title);
    }

    private static final net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer PLAIN =
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();

    // ------------------------------------------------------------------- placing things

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    protected void set(int slot, ItemStack item, Consumer<InventoryClickEvent> handler) {
        boolean allowed = writingChrome ? layout.acceptsChrome(slot) : layout.accepts(slot);
        if (!allowed) {
            return;
        }
        getInventory().setItem(slot, item);
        if (handler != null) {
            handlers.put(slot, handler);
        }
    }

    /**
     * A button in one of the three bands.
     *
     * <p>The column says where this comes in the band's order and how far it stands from its
     * neighbours — not which slot it lands on. See {@link MenuLayout}.
     */
    protected void band(int band, int column, ItemStack item) {
        band(band, column, item, null);
    }

    protected void band(int band, int column, ItemStack item,
                        Consumer<InventoryClickEvent> handler) {
        pendingBands.computeIfAbsent(Math.max(MenuLayout.WHO, Math.min(MenuLayout.LAND, band)),
                        row -> new TreeMap<>())
                .put(Math.max(1, Math.min(7, column)), new Placement(item, handler));
    }

    /**
     * A button the viewer may not use: shown, greyed, with the reason — rather than one that looks
     * live and answers a click with an error.
     *
     * @param allowed whether this viewer may use it
     * @param reason  who it belongs to instead, e.g. "The owner's to set"
     */
    protected void band(int band, int column, boolean allowed, ItemStack item, String reason,
                        Consumer<InventoryClickEvent> handler) {
        if (allowed) {
            band(band, column, item, handler);
        } else {
            band(band, column, Icons.locked(item, reason));
        }
    }

    /**
     * A cell of a full-width grid — for a page whose content is a set of equal things rather than a
     * handful of named doors: the permission grid of a trusted player, the nine hotbar keys of an
     * auto-equip rule.
     *
     * <p>Deliberately allowed to use the frame columns: a grid that skipped two columns in every row
     * would read as a broken band rather than as a grid.
     */
    protected void cell(int row, int column, ItemStack item,
                        Consumer<InventoryClickEvent> handler) {
        pendingCells.computeIfAbsent(Math.max(0, Math.min(4, row)), key -> new TreeMap<>())
                .put(Math.max(0, Math.min(8, column)), new Placement(item, handler));
    }

    /** A tool in the toolbar row; the column orders it, as in a band. */
    /**
     * A toolbar button the viewer may not use: shown, greyed, with the reason.
     *
     * <p>The same courtesy {@link #band(int, int, boolean, ItemStack, String, Consumer)} already offered,
     * missing from the toolbar — so every page with a conditional toolbar button drew it only when it worked
     * and left a hole when it did not. A missing button cannot be explained: somebody looking for it does not
     * learn that the round has moved on, they learn that the page is different from the one they remember. It
     * also shifts everything after it, so the button they were reaching for is somewhere else under their
     * cursor.
     *
     * @param allowed whether this viewer may use it right now
     * @param reason  why not, in a sentence — "Teams are settled for this round"
     */
    protected void toolbar(int column, boolean allowed, ItemStack item, String reason,
                           Consumer<InventoryClickEvent> handler) {
        if (allowed) {
            toolbar(column, item, handler);
        } else {
            toolbar(column, Icons.locked(item, reason), event -> { });
        }
    }

    protected void toolbar(int column, ItemStack item, Consumer<InventoryClickEvent> handler) {
        pendingToolbar.put(Math.max(1, Math.min(7, column)), new Placement(item, handler));
    }

    /**
     * The one irreversible action of this page, always on the same slot.
     *
     * <p>Being flanked by navigation is the cost of that position, which is why this is only ever
     * given a button that opens a confirmation — a misclick costs a second page, never the thing.
     */
    protected void danger(ItemStack item, Consumer<InventoryClickEvent> handler) {
        pendingDanger = new Placement(item, handler);
    }

    /** Writes the buffered bands, grids and toolbar, each centred within the columns it may use. */
    private void layoutBands() {
        for (Map.Entry<Integer, NavigableMap<Integer, Placement>> band : pendingBands.entrySet()) {
            place(band.getValue(), band.getKey(), 1, 7);
        }
        for (Map.Entry<Integer, NavigableMap<Integer, Placement>> grid : pendingCells.entrySet()) {
            place(grid.getValue(), grid.getKey(), 0, 8);
        }
        place(pendingToolbar, MenuLayout.TOOLBAR_ROW, 1, 7);
        // Emptied as it is written, because this runs twice per render — once for what render() asked for and
        // once for what decorate() did. Left full, a band of three would be laid out again as a band of six and
        // centre itself somewhere neither belongs.
        pendingBands.clear();
        pendingCells.clear();
        pendingToolbar.clear();
    }

    private void place(NavigableMap<Integer, Placement> row, int rowIndex,
                       int firstColumn, int lastColumn) {
        Map<Integer, Integer> slots = MenuLayout.placeRow(new ArrayList<>(row.keySet()), rowIndex,
                firstColumn, lastColumn);
        for (Map.Entry<Integer, Integer> placed : slots.entrySet()) {
            Placement placement = row.get(placed.getKey());
            set(placed.getValue(), placement.item(), placement.handler());
        }
    }

    /**
     * Fills what {@link #render()} left empty.
     *
     * <p>Two materials, meaning different things: grey is the frame the page is built out of, and
     * black is the quiet field the buttons sit on. One material for both — which is what this used
     * to be — made a hub read as an undifferentiated wall of glass with icons lost in it.
     */
    protected void decorate() {
        if (!layout.hasFullChrome()) {
            fillEmpty(Material.GRAY_STAINED_GLASS_PANE);
            return;
        }
        ItemStack frame = Icons.filler(Material.GRAY_STAINED_GLASS_PANE);
        for (int band = MenuLayout.WHO; band <= MenuLayout.LAND; band++) {
            setIfEmpty(band * 9, frame);
            setIfEmpty(band * 9 + 8, frame);
        }
        fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
    }

    private void setIfEmpty(int slot, ItemStack item) {
        if (slot < layout.size() && getInventory().getItem(slot) == null) {
            getInventory().setItem(slot, item);
        }
    }

    /** Fills every empty slot with a decorative pane. */
    protected void fillEmpty(Material material) {
        ItemStack filler = Icons.filler(material);
        for (int slot = 0; slot < layout.size(); slot++) {
            if (getInventory().getItem(slot) == null) {
                getInventory().setItem(slot, filler);
            }
        }
    }

    protected void fillRow(int row, Material material) {
        ItemStack filler = Icons.filler(material);
        for (int column = 0; column < 9; column++) {
            int slot = row * 9 + column;
            if (slot < layout.size() && getInventory().getItem(slot) == null) {
                getInventory().setItem(slot, filler);
            }
        }
    }

    // ------------------------------------------------------------------------- chrome

    private void paintChrome() {
        writingChrome = true;
        try {
            int row = layout.chromeRowStart();
            if (parent != null) {
                set(row + MenuLayout.CHROME_BACK, Icons.back(parent.breadcrumb()),
                        event -> parent.open());
            }
            if (layout.hasFullChrome()) {
                Menu home = root();
                // Only when it goes somewhere Back does not already, otherwise it is a second
                // button doing the same thing.
                if (home != this && home != parent) {
                    set(row + MenuLayout.CHROME_HOME, Icons.home(home.breadcrumb()),
                            event -> home.open());
                }
                paintPagingChrome(row);
                List<String> help = helpLines();
                if (!help.isEmpty()) {
                    set(row + MenuLayout.CHROME_HELP, Icons.help(help), this::onHelp);
                }
            }
            if (pendingDanger != null) {
                set(row + MenuLayout.CHROME_DANGER, pendingDanger.item(), pendingDanger.handler());
            }
            set(row + MenuLayout.CHROME_CLOSE, Icons.close(), event -> viewer.closeInventory());
            fillRow(layout.rows() - 1, Material.GRAY_STAINED_GLASS_PANE);
        } finally {
            writingChrome = false;
        }
    }

    /** Overridden by {@link PaginatedMenu}; a plain page has nothing to page. */
    protected void paintPagingChrome(int chromeRow) {
        // Nothing by default.
    }

    /**
     * What this page is for, in the viewer's own words. Empty means no help button.
     *
     * <p>Every page can answer "what am I looking at" without the player having to leave it and find
     * the chapter in the manual.
     */
    protected List<String> helpLines() {
        return List.of();
    }

    /** What the help button does beyond showing its lore. */
    protected void onHelp(InventoryClickEvent event) {
        // Default: the lore was the whole answer.
    }

    /** The top of the chain this menu was opened from — where Home goes. */
    private Menu root() {
        Menu candidate = this;
        while (candidate.parent != null) {
            candidate = candidate.parent;
        }
        return candidate;
    }

    /**
     * The whole window title: the brand, the page this was opened from, and this page.
     *
     * <p>A chest menu has no other chrome. There is a Back button but nothing saying what Back goes back to,
     * so three levels in the title read "Trusted people" and the player had to remember which claim they
     * opened it from. With the parent in front of it the title is the only orientation the window needs:
     *
     * <pre>Claims » claimtrials › Trusted people</pre>
     *
     * <p><b>One level, not the chain.</b> Minecraft clips a window title to a pixel budget by cutting the end
     * off, so a full path spends the budget on where you came from and loses where you are — which is the
     * half that matters. Two names fit, and the second survives.
     *
     * <p>Composed from Components rather than by joining MiniMessage strings, because a parent's name can be
     * a claim name somebody chose: {@code <red>} must appear as those six characters rather than colour the
     * rest of the title, and {@code <click:run_command:...>} must never become a click event.
     */
    public Component windowTitle() {
        return brand.trail(parentTitle(), title());
    }

    /**
     * The parent page's name, or nothing if it can no longer say.
     *
     * <p>A parent's title is usually built from the thing that page is about, and the child outlives that:
     * open a claim's member list, have the claim deleted underneath you, click anything, and the parent's
     * title throws while the <em>child</em> is the page being drawn. Before there was a trail at all, a child
     * was insulated from its parent's state once opened, and it has to stay that way — losing a breadcrumb is
     * cosmetic, losing the window is not.
     */
    private Component parentTitle() {
        if (parent == null) {
            return null;
        }
        try {
            return parent.title();
        } catch (RuntimeException gone) {
            return null;
        }
    }

    /** Human-readable name, used in the Back button of a child page. */
    public String breadcrumb() {
        return "the previous menu";
    }

    // ------------------------------------------------------------------------- events

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Consumer<InventoryClickEvent> handler = handlers.get(event.getRawSlot());
        if (handler == null) {
            return;
        }
        try {
            handler.accept(event);
        } catch (RuntimeException failure) {
            // A click that throws must not leave the player stuck in a window with a cancelled
            // event and no feedback. The page stays open; the log gets the reason.
            log.error(failure, "A click on {} failed.", getClass().getSimpleName());
        }
    }

    /**
     * Whether clicks in the player's own inventory are their business.
     *
     * <p>False for every screen that is a set of buttons — shift-clicking from below would otherwise
     * post items into a menu with nowhere to put them. The editable screens override it, and answer
     * {@link #handleBottomClick} too — saying yes here without also handling the click is what would
     * hand vanilla's own shift-click straight to a top inventory that has no idea an item arrived.
     */
    public boolean allowBottomInventoryInteraction() {
        return false;
    }

    /**
     * Whether a player may put an item into this slot of the window — by dragging, or by a click that
     * {@link #handleClick} leaves to the game.
     *
     * <p>False everywhere by default. {@link MenuListener} asks it for every slot of a drag, so a screen that
     * has opted into {@link #allowBottomInventoryInteraction} for the sake of reading items still cannot have
     * one dragged onto a button.
     */
    public boolean acceptsItemsIn(int rawSlot) {
        return false;
    }

    /**
     * A click in the player's own inventory, for a screen that said yes to {@link
     * #allowBottomInventoryInteraction}. Never called otherwise.
     *
     * <p>Cancelled by default, which is deliberately not "do nothing": a screen that opts in here but
     * forgets to override this would otherwise let vanilla's own shift-click post an item into whatever
     * the top inventory happens to be showing at that slot, silently overwriting a rendered button
     * rather than being read as an item nobody asked to store.
     */
    public void handleBottomClick(InventoryClickEvent event) {
        event.setCancelled(true);
    }

    /** Called when the view closes. Editable screens use it to flush a drag that had no click. */
    public void handleClose(InventoryCloseEvent event) {
        // Default: nothing to clean up.
    }

    /** Closes this view and goes back where it was opened from, or out of the GUI entirely. */
    protected void leave() {
        if (parent != null) {
            parent.open();
        } else {
            viewer.closeInventory();
        }
    }

    public Player viewer() {
        return viewer;
    }

    public Menu parent() {
        return parent;
    }

    public void parent(Menu parent) {
        this.parent = parent;
    }
}
