package de.raindancer.core.ui.hub;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/** A hub screen: a header the subclass draws, and tiles that fill the three bands, paged. */
abstract class HubPage extends Menu {

    /** What one tile is and what a click on it does. */
    record Tile(ItemStack icon, Consumer<InventoryClickEvent> onClick) {
    }

    private static final int PER_BAND = 7;
    static final int PER_PAGE = PER_BAND * 3;

    private int page;

    HubPage(Player viewer, Brand brand, Menu parent) {
        super(viewer, brand, parent);
    }

    abstract void header();

    abstract List<Tile> tiles();

    abstract ItemStack emptyIcon();

    @Override
    public void open() {
        super.open();
        chime(1.3f);
    }

    @Override
    protected void render() {
        header();
        List<Tile> all = tiles();
        int pages = MenuLayout.pageCount(all.size(), PER_PAGE);
        page = MenuLayout.clampPage(page, pages);
        if (all.isEmpty()) {
            band(MenuLayout.RULES, 4, emptyIcon());
            return;
        }
        int from = MenuLayout.pageStart(page, PER_PAGE);
        int to = Math.min(all.size(), from + PER_PAGE);
        for (int index = from; index < to; index++) {
            int offset = index - from;
            Tile tile = all.get(index);
            band(MenuLayout.WHO + offset / PER_BAND, offset % PER_BAND + 1, tile.icon(), event -> {
                chime(1.0f);
                tile.onClick().accept(event);
            });
        }
    }

    @Override
    protected void paintPagingChrome(int chromeRow) {
        int pages = MenuLayout.pageCount(tiles().size(), PER_PAGE);
        if (pages <= 1) {
            return;
        }
        if (page > 0) {
            set(chromeRow + MenuLayout.CHROME_PREVIOUS, Icons.previousPage(page, pages), turnTo(page - 1));
        }
        if (page < pages - 1) {
            set(chromeRow + MenuLayout.CHROME_NEXT, Icons.nextPage(page + 2, pages), turnTo(page + 1));
        }
        set(chromeRow + MenuLayout.CHROME_PAGE, Icons.pageCounter(page + 1, pages));
    }

    private Consumer<InventoryClickEvent> turnTo(int newPage) {
        return event -> {
            page = newPage;
            chime(1.1f);
            refresh();
        };
    }

    final void chime(float pitch) {
        viewer().playSound(viewer().getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, pitch);
    }

    /** Hides the vanilla stat lines a sword or shovel would add under the lore, and optionally makes it shine. */
    static ItemStack tidy(ItemStack item, boolean shine) {
        item.editMeta(meta -> {
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES,
                    org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            if (shine) {
                meta.setEnchantmentGlintOverride(true);
            }
        });
        return item;
    }

    /** Whether a command name is registered, so a shortcut to a plugin that is not installed is not drawn. */
    static boolean exists(String command) {
        return Bukkit.getCommandMap().getCommand(command) != null;
    }
}
