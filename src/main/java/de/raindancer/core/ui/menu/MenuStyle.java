package de.raindancer.core.ui.menu;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Who sees painted menus: whoever is wearing the server's pack, because the painting is in it — as long as the owner
 * has not switched them off for the server ({@code menus-painted}) and the player not for themselves
 * ({@link #PAINTED}). Installed by Core when its resource packs are up; until then — and in tests — every window is
 * the classic one.
 */
public final class MenuStyle {

    public static final String NAMESPACE = "rainscore";
    /** A player's own "painted menus" switch, flipped from their profile. */
    public static final de.raindancer.core.ui.profile.PlayerSwitch PAINTED =
            new de.raindancer.core.ui.profile.PlayerSwitch(NAMESPACE, "painted-menus", true);

    private static volatile Predicate<UUID> wearing = id -> false;

    private MenuStyle() {
    }

    public static void install(Predicate<UUID> check) {
        wearing = check == null ? id -> false : check;
    }

    /**
     * @param wearing  whether the player has the pack on
     * @param serverOn the owner's setting for the whole server
     * @param playerOn the player's own switch
     */
    public static void install(Predicate<UUID> wearing, java.util.function.BooleanSupplier serverOn,
                               Predicate<UUID> playerOn) {
        if (wearing == null || serverOn == null || playerOn == null) {
            uninstall();
            return;
        }
        install(id -> serverOn.getAsBoolean() && wearing.test(id) && playerOn.test(id));
    }

    public static void uninstall() {
        wearing = id -> false;
    }

    public static boolean wearing(UUID player) {
        try {
            return player != null && wearing.test(player);
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /** A slot that shows the painting under it: an item with no model and no tooltip. */
    public static ItemStack blank() {
        ItemStack blank = new ItemStack(Material.PAPER);
        ItemMeta meta = blank.getItemMeta();
        if (meta != null) {
            meta.setItemModel(new NamespacedKey(NAMESPACE, "ui/blank"));
            blank.setItemMeta(meta);
        }
        blank.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return blank;
    }
}
