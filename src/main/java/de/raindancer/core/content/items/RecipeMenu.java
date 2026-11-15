package de.raindancer.core.content.items;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.choose.ItemChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * What a custom item is crafted from, as the three-by-three grid it actually is.
 *
 * <h2>Why Core owns this page</h2>
 * The same argument as the cues. Core owns {@link CustomItem}, {@link CustomItems} and
 * {@link ItemRecipes} — so a page that edits a recipe belongs here, once, for every plugin's items rather
 * than once per plugin. The Hunger Games plugin this replaces had a page for exactly one recipe
 * ({@code items.exmatrikulator.recipe}, three rows of text in a config file), and the port dropped it because
 * the item had moved into this registry and the registry had no screen.
 *
 * <h2>The mistake the grid makes easy, and what this page does about it</h2>
 * Nine slots, and only the middle filled, is not "one item in the middle" as far as Bukkit is concerned — it
 * is a three-by-three shape whose outer ring is empty, and the server refuses it with an exception naming none
 * of this. {@link ItemRecipes#crop} is what turns a drawn grid into the shape a server will accept, and this
 * page shows the cropped result <em>before</em> saving: a preview that disagrees with the result is worse than
 * no preview.
 *
 * <h2>Nothing is saved until Save</h2>
 * The grid is edited in memory and written on one click, so backing out changes nothing — the same rule
 * {@code AmountChooser} follows, and the right one for a page where a wrong recipe means an item nobody can
 * make.
 */
public final class RecipeMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Where the nine grid slots sit: a three-by-three block, left of the middle. */
    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};

    /** How the crafting grid is written: three rows of three names, {@code AIR} for an empty slot. */
    private static final int WIDTH = 3;

    private final CustomItems items;
    private final CustomItem item;
    private final Runnable save;

    /** The grid being edited, nine cells, {@code null} for empty. Not saved until Save is clicked. */
    private final Material[] grid = new Material[9];

    public RecipeMenu(Player viewer, Brand brand, Menu parent, CustomItems items, CustomItem item,
                      Runnable save) {
        super(viewer, brand, parent, 5);
        this.items = items;
        this.item = item;
        this.save = save;
        readInto(grid, item.recipe());
    }

    /**
     * Fills a nine-cell grid from however many rows an item has.
     *
     * <p>Top-left aligned, because that is where a cropped recipe came from: an item stored as one row of one
     * name was drawn in the middle by the version of this that centred it, and saving without touching
     * anything then moved the recipe. A page that changes something by being opened is worse than one that
     * looks slightly off.
     */
    static void readInto(Material[] grid, List<String> rows) {
        for (int row = 0; row < Math.min(WIDTH, rows.size()); row++) {
            String[] cells = rows.get(row).trim().split("\\s+");
            for (int column = 0; column < Math.min(WIDTH, cells.length); column++) {
                grid[row * WIDTH + column] = Material.matchMaterial(cells[column].trim());
            }
        }
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Recipe: " + item.id());
    }

    @Override
    public String breadcrumb() {
        return "Recipe";
    }

    @Override
    protected void render() {
        for (int cell = 0; cell < GRID.length; cell++) {
            int index = cell;
            set(GRID[cell], cellIcon(grid[cell]), click -> {
                if (click.isRightClick()) {
                    // Right-click empties. Otherwise the only way to remove an ingredient is to find "air" in
                    // a catalogue of a thousand materials, which nobody will.
                    grid[index] = null;
                    refresh();
                    return;
                }
                new ItemChooser(viewer, brand(), this, "Ingredient for slot " + (index + 1),
                        // Already a Material — ItemChooser hands one over rather than a name, so nothing
                        // here has to look it up and nothing can fail to.
                        picked -> {
                            grid[index] = picked;
                            refresh();
                        }).open();
            });
        }

        // What it will actually be, cropped, beside the grid rather than after saving.
        List<String> cropped = ItemRecipes.crop(asRows());
        set(16, Icons.of(item.material(), "<white>" + item.nameOrId(), previewLore(cropped)));

        toolbar(2, Icons.of(Material.LIME_CONCRETE, "<green>Save",
                        cropped.isEmpty()
                                ? List.of("<gray>This will make the item uncraftable.",
                                        "<dark_gray>Which is a legitimate thing to want.")
                                : List.of("<gray>Written to the item; the server takes it up on its next restart.",
                                        "<dark_gray>Existing recipes are replaced on the next restart.")),
                click -> saveIt(cropped));

        toolbar(4, Icons.of(Material.BARRIER, "<yellow>Clear the grid",
                        List.of("<gray>Empties every slot.",
                                "<dark_gray>Nothing is saved until you click Save.")),
                click -> {
                    Arrays.fill(grid, null);
                    refresh();
                });

        toolbar(6, Icons.of(Material.STRUCTURE_BLOCK, "<yellow>Back to what it was",
                        List.of("<gray>The recipe this item currently has.",
                                "<dark_gray>Undoes everything since this page opened.")),
                click -> {
                    Arrays.fill(grid, null);
                    readInto(grid, item.recipe());
                    refresh();
                });
    }

    /** One slot: what is in it, or an invitation to put something there. */
    private ItemStack cellIcon(Material material) {
        if (material == null || material == Material.AIR) {
            return Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>Empty",
                    List.of("<gray>Click to choose an ingredient."));
        }
        return Icons.of(material, "<white>" + readable(material),
                List.of("<gray>Left-click: change it.", "<aqua>Right-click: empty this slot."));
    }

    /** What the recipe will be, in words, so nobody has to guess what cropping did. */
    private List<String> previewLore(List<String> cropped) {
        List<String> lore = new ArrayList<>();
        if (cropped.isEmpty()) {
            lore.add("<dark_gray>Nothing in the grid.");
            lore.add("<gray>Saving would make this uncraftable.");
            return lore;
        }
        lore.add("<yellow>The shape this becomes:");
        cropped.forEach(row -> lore.add("<dark_gray> " + row));
        if (cropped.size() != rowsUsed() || widthOf(cropped) != WIDTH) {
            // Said out loud: a grid with an empty outer ring is a shape Bukkit refuses, and cropping is what
            // makes it acceptable. Somebody who is not told will think the page lost their layout.
            lore.add("");
            lore.add("<gray>Empty rows and columns are trimmed: a shape with an");
            lore.add("<gray>empty edge is one the server refuses.");
        }
        return lore;
    }

    private int rowsUsed() {
        int used = 0;
        for (int row = 0; row < WIDTH; row++) {
            for (int column = 0; column < WIDTH; column++) {
                if (grid[row * WIDTH + column] != null) {
                    used++;
                    break;
                }
            }
        }
        return used;
    }

    private static int widthOf(List<String> rows) {
        return rows.stream().mapToInt(row -> row.trim().split("\\s+").length).max().orElse(0);
    }

    /** The grid as the three rows an item stores. */
    private List<String> asRows() {
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < WIDTH; row++) {
            List<String> cells = new ArrayList<>();
            for (int column = 0; column < WIDTH; column++) {
                Material cell = grid[row * WIDTH + column];
                cells.add(cell == null ? "AIR" : cell.name());
            }
            rows.add(String.join(" ", cells));
        }
        return rows;
    }

    private void saveIt(List<String> cropped) {
        items.define(item.withRecipe(cropped));
        if (save != null) {
            save.run();
        }
        // Named honestly: Bukkit registers recipes at start-up, so a changed one is stored now and crafted
        // after a restart. Claiming otherwise would send somebody to a crafting table to be disappointed.
        if (cropped.isEmpty()) {
            viewer.sendMessage(Messages.spoken("items.no-recipe",
                    "<yellow><item> has no recipe any more.", "item", item.id()));
        } else {
            tell("<green>✔ Recipe saved. <gray>It can be crafted after the next restart.</gray>");
        }
        backToWhoeverOpenedThis();
    }

    private static String readable(Material material) {
        String words = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private void tell(String miniMessage) {
        viewer.sendMessage(MINI.deserialize(miniMessage));
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>The crafting grid for <white>" + item.key() + "</white>.",
                "",
                "<yellow>Left-click a slot</yellow> <gray>to choose an ingredient.</gray>",
                "<aqua>Right-click a slot</aqua> <gray>to empty it.</gray>",
                "",
                "<gray>Empty rows and columns are trimmed when saved: a shape",
                "<gray>with an empty edge is one the server refuses.");
    }
}
