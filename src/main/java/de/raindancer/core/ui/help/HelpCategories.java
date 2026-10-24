package de.raindancer.core.ui.help;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;

/**
 * The shelves topics stand on. A topic may name any category — these are the ones the guide knows an icon and a
 * place in the order for, so that two plugins writing "Economy" and "economy" end up on one shelf.
 */
public final class HelpCategories {

    public static final String START = "Getting started";
    public static final String OTHER = "Everything else";

    private record Known(String name, Material icon) {
    }

    private static final List<Known> KNOWN = List.of(
            new Known(START, Material.COMPASS),
            new Known("Economy & trade", Material.GOLD_INGOT),
            new Known("Land & building", Material.GRASS_BLOCK),
            new Known("Getting around", Material.ENDER_PEARL),
            new Known("Chat & community", Material.WRITABLE_BOOK),
            new Known("Guilds & teams", Material.WHITE_BANNER),
            new Known("Progress & rewards", Material.EXPERIENCE_BOTTLE),
            new Known("Items & enchantments", Material.ENCHANTED_BOOK),
            new Known("Games", Material.TARGET),
            new Known("Looks & cosmetics", Material.LEATHER_CHESTPLATE),
            new Known("Worlds", Material.FILLED_MAP),
            new Known("Staff", Material.IRON_AXE),
            new Known(OTHER, Material.BOOKSHELF));

    private HelpCategories() {
    }

    /** The known spelling of a category, so "economy & Trade" and "Economy & trade" are one shelf. */
    public static String canonical(String category) {
        if (category == null || category.isBlank()) {
            return OTHER;
        }
        for (Known known : KNOWN) {
            if (known.name().equalsIgnoreCase(category.strip())) {
                return known.name();
            }
        }
        return category.strip();
    }

    /** Where a category stands: the known ones in their order, the rest after them alphabetically. */
    public static int rank(String category) {
        for (int index = 0; index < KNOWN.size(); index++) {
            if (KNOWN.get(index).name().equals(category)) {
                // Everything else and Staff go last, after categories nobody here has heard of.
                return category.equals(OTHER) || category.equals("Staff") ? 1_000 + index : index;
            }
        }
        return 500;
    }

    public static Material icon(String category) {
        for (Known known : KNOWN) {
            if (known.name().equals(category)) {
                return known.icon();
            }
        }
        return Material.BOOK;
    }

    /** Every name the guide knows, for an author looking for the right shelf. */
    public static List<String> names() {
        return KNOWN.stream().map(Known::name).toList();
    }

    static String key(String category) {
        return canonical(category).toLowerCase(Locale.ROOT);
    }
}
