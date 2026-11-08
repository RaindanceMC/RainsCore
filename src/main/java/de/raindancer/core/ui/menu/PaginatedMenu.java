package de.raindancer.core.ui.menu;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * A page of a list too long to fit in one window.
 *
 * <h2>What this gets right that the old one did not</h2>
 * The framework this replaces let a paged screen paint its own footer, which meant a list with a
 * footer of its own silently overwrote its page arrows — a list that could not be paged past the
 * first screen, with nothing to show for it. Here the paging is chrome, painted by the framework
 * after the page has had its say, and {@link MenuLayout} does the arithmetic where it can be tested.
 *
 * <p>A subclass supplies the entries and how to draw one. Everything else — how many pages there
 * are, which slice this page shows, what happens at either end of the list — is here.
 *
 * @param <T> what the list is of
 */
public abstract class PaginatedMenu<T> extends Menu {

    /** Rows the entries fill: everything above the toolbar and the chrome. */
    private static final int ENTRY_ROWS = 4;
    /** How many entries fit on one page. */
    public static final int PER_PAGE = ENTRY_ROWS * 9;

    private int page;

    protected PaginatedMenu(Player viewer, Brand brand, Menu parent) {
        super(viewer, brand, parent);
    }

    /** Everything in the list, in the order it should be read. */
    protected abstract List<T> entries();

    /** One entry as a button. */
    protected abstract ItemStack icon(T entry);

    /** What clicking that entry does. */
    protected abstract void onClick(T entry, InventoryClickEvent event);

    /** What to show when the list is empty — a page saying so beats a window that will not open. */
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<" + Style.itemLore() + ">Nothing here yet");
    }

    /**
     * What clicking that icon does, for a list whose empty state has a way out of it.
     *
     * <p>Does nothing by default, so every existing list behaves exactly as it did and only the screens with
     * something to offer opt in.
     *
     * <p>Worth having because the empty icon usually names the way out — "start one with /claim new" — and being
     * unclickable made it read as broken rather than as instructions. Everything else on a page is clickable; the
     * one thing that is not is the one thing a player tries first.
     */
    protected void emptyAction(InventoryClickEvent event) {
    }

    @Override
    protected void render() {
        List<T> all = entries();
        int pages = MenuLayout.pageCount(all.size(), PER_PAGE);
        page = MenuLayout.clampPage(page, pages);

        if (all.isEmpty()) {
            set(MenuLayout.bandSlot(MenuLayout.RULES, 4), emptyIcon(), this::emptyAction);
            return;
        }
        int from = MenuLayout.pageStart(page, PER_PAGE);
        int to = Math.min(all.size(), from + PER_PAGE);
        for (int index = from; index < to; index++) {
            T entry = all.get(index);
            set(index - from, icon(entry), event -> onClick(entry, event));
        }
    }

    /**
     * The entry area is left clear rather than filled with panes.
     *
     * <p>A half-full page of a list reads as a list with a few things in it; the same page with the
     * gaps filled in reads as a broken grid.
     */
    @Override
    protected void decorate() {
        fillRow(MenuLayout.TOOLBAR_ROW, Material.BLACK_STAINED_GLASS_PANE);
    }

    @Override
    protected void paintPagingChrome(int chromeRow) {
        int pages = MenuLayout.pageCount(entries().size(), PER_PAGE);
        if (pages <= 1) {
            return;
        }
        if (page > 0) {
            set(chromeRow + MenuLayout.CHROME_PREVIOUS, Icons.previousPage(page, pages),
                    turnTo(page - 1));
        }
        if (page < pages - 1) {
            set(chromeRow + MenuLayout.CHROME_NEXT, Icons.nextPage(page + 2, pages),
                    turnTo(page + 1));
        }
        // The counter goes in the middle — unless this page has a destructive button, which owns
        // that slot and is the more important of the two.
        if (!hasDanger()) {
            set(chromeRow + MenuLayout.CHROME_PAGE, Icons.pageCounter(page + 1, pages));
        }
    }

    private Consumer<InventoryClickEvent> turnTo(int newPage) {
        return event -> {
            page = newPage;
            refresh();
        };
    }

    /** A page turn sounds like one; everything else on the page clicks. */
    @Override
    protected String clickCue(int rawSlot) {
        int chromeRow = layout().chromeRowStart();
        if (rawSlot == chromeRow + MenuLayout.CHROME_PREVIOUS || rawSlot == chromeRow + MenuLayout.CHROME_NEXT) {
            return de.raindancer.core.ui.effect.Cues.PAGE;
        }
        return super.clickCue(rawSlot);
    }

    /** Whether this page put a destructive button on the chrome row, which the counter must dodge. */
    protected boolean hasDanger() {
        return false;
    }

    /** Which page is being shown, counting from zero. */
    public int page() {
        return page;
    }
}
