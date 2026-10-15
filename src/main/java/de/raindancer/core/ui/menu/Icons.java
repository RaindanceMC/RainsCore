package de.raindancer.core.ui.menu;

import de.raindancer.core.ui.chat.Style;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import de.raindancer.core.RainsCore;
import de.raindancer.core.ui.messages.Messages;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.UUID;

import java.util.ArrayList;
import java.util.List;

/**
 * The buttons every menu is made of.
 *
 * <h2>Why the italics are turned off everywhere</h2>
 * Minecraft draws a custom item name in italics unless something says otherwise, and an unspecified
 * decoration inherits from its parent. A button whose name is not explicitly non-italic therefore
 * comes out slanted, which is why the old menus were a patchwork of upright and italic labels
 * depending on which file built them. Every name and every lore line here says so out loud.
 *
 * <p>Colours come from {@link Style}, so a server that changes its palette changes every button in
 * every plugin at once.
 */
public final class Icons {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Icons() {
    }

    /**
     * What a button is drawn as: the material itself, or for a block nobody can hold (a portal, water, fire)
     * the nearest thing that is an item. An ItemStack of a non-item throws, which took a whole settings page down.
     */
    public static Material shown(Material material) {
        if (material == null) {
            return Material.STONE;
        }
        if (material.isItem() && !material.isAir()) {
            return material;
        }
        return switch (material) {
            case NETHER_PORTAL -> Material.OBSIDIAN;
            case END_PORTAL, END_GATEWAY -> Material.END_PORTAL_FRAME;
            case WATER, BUBBLE_COLUMN -> Material.WATER_BUCKET;
            case LAVA -> Material.LAVA_BUCKET;
            case FIRE, SOUL_FIRE -> Material.FLINT_AND_STEEL;
            default -> Material.STONE;
        };
    }

    /** A button: an icon, a name, and however many lines of explanation. All MiniMessage. */
    public static ItemStack of(Material material, String name, String... lore) {
        return of(material, name, List.of(lore));
    }

    public static ItemStack of(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(shown(material));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(text(name == null ? "" : name, Style.itemName()));
            List<Component> lines = new ArrayList<>(lore.size());
            for (String line : lore) {
                lines.add(text(line, Style.itemLore()));
            }
            meta.lore(lines);
            item.setItemMeta(meta);
        }
        hideAttributes(item);
        return item;
    }

    /**
     * A button is not a weapon: a pickaxe shown for "dig" must not explain its attack damage under the words. The game
     * writes those lines for any tool, sword or armour, so they are hidden on every button.
     */
    private static void hideAttributes(ItemStack item) {
        try {
            item.setData(io.papermc.paper.datacomponent.DataComponentTypes.TOOLTIP_DISPLAY,
                    io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay()
                            .addHiddenComponents(io.papermc.paper.datacomponent.DataComponentTypes.ATTRIBUTE_MODIFIERS)
                            .build());
        } catch (RuntimeException | LinkageError noServer) {
            // Without a running server (a test) there are no data components to set, and nothing is shown anyway.
        }
    }

    /**
     * A button showing somebody's actual head.
     *
     * <p>Their skin, not Steve's. A menu that says "Your settings" over a default head is a menu
     * that has not noticed who is looking at it, and one listing players as identical heads is a
     * list you have to read rather than recognise — which is the whole reason heads are used for
     * players in the first place.
     *
     * <p>Falls back to a plain head rather than failing: a player the server has never seen, or one
     * whose skin has not loaded yet, still needs a button.
     */
    public static ItemStack head(OfflinePlayer who, String name, String... lore) {
        return head(who, name, List.of(lore));
    }

    private static final HeadSkins SKINS = new HeadSkins();

    public static ItemStack head(OfflinePlayer who, String name, List<String> lore) {
        ItemStack item = of(Material.PLAYER_HEAD, name, lore);
        if (who == null) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            HeadSkins.Skin skin = SKINS.of(who);
            if (skin.profile() != null) {
                skull.setPlayerProfile(skin.profile());
            } else if (skin.lookUp()) {
                // setOwningPlayer rather than the deprecated owner-by-name: a name lookup blocks on
                // Mojang's servers, and doing that while drawing a menu freezes the main thread for
                // everybody until it answers.
                skull.setOwningPlayer(who);
            }
            item.setItemMeta(skull);
        }
        return item;
    }

    /** The same, by id — for a player who is not online and may never have been seen. */
    public static ItemStack head(UUID who, String name, String... lore) {
        return head(who, name, List.of(lore));
    }

    /** The same for a lore built up as a list, which is what any head with conditional lines has. */
    public static ItemStack head(UUID who, String name, List<String> lore) {
        return head(who == null ? null : Bukkit.getOfflinePlayer(who), name, lore);
    }

    /**
     * A button that cannot be used, and the reason why.
     *
     * <p>Shown rather than hidden: a player who cannot see a button assumes the feature does not
     * exist, and a player who clicks a live-looking one and gets an error learns to distrust the
     * menu. Greyed with the reason underneath answers the question before it is asked.
     */
    public static ItemStack locked(ItemStack original, String reason) {
        ItemStack item = original == null
                ? new ItemStack(Material.GRAY_DYE)
                : original.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Component name = meta.displayName();
            if (name != null) {
                meta.displayName(name.colorIfAbsent(
                        NamedTextColor.GRAY));
            }
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            lore.add(text("<" + Style.bad() + ">"
                    + (reason == null ? word("menu.locked", "Not yours to change") : reason),
                    Style.bad()));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** A decorative pane. Nameless, so hovering one says nothing rather than saying "Glass Pane". */
    public static ItemStack filler(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack back(String where) {
        String to = where == null ? word("menu.previous-menu", "the previous menu") : where;
        return of(Material.ARROW, "<" + Style.itemName() + ">" + word("menu.back", "Back"),
                "<" + Style.itemLore() + ">" + word("menu.back-to", "Back to <where>",
                        "where", to));
    }

    public static ItemStack home(String where) {
        return of(Material.COMPASS,
                "<" + Style.itemName() + ">" + word("menu.home", "Main menu"),
                "<" + Style.itemLore() + ">" + word("menu.back-to", "Back to <where>",
                        "where", where == null ? "the start" : where));
    }

    public static ItemStack close() {
        return of(Material.BARRIER, "<" + Style.bad() + ">" + word("menu.close", "Close"),
                "<" + Style.itemLore() + ">" + word("menu.close-lore", "Shut this menu"));
    }

    public static ItemStack help(List<String> lines) {
        List<String> lore = new ArrayList<>();
        for (String line : lines) {
            lore.add("<" + Style.itemLore() + ">" + line);
        }
        return of(Material.WRITABLE_BOOK,
                "<" + Style.itemName() + ">" + word("menu.help", "What is this?"), lore);
    }

    public static ItemStack previousPage(int page, int pages) {
        return of(Material.SPECTRAL_ARROW,
                "<" + Style.itemName() + ">" + word("menu.previous-page", "Previous page"),
                "<" + Style.itemLore() + ">" + pageOf(page, pages));
    }

    public static ItemStack nextPage(int page, int pages) {
        return of(Material.SPECTRAL_ARROW,
                "<" + Style.itemName() + ">" + word("menu.next-page", "Next page"),
                "<" + Style.itemLore() + ">" + pageOf(page, pages));
    }

    private static String pageOf(int page, int pages) {
        return word("menu.page-of", "Page <page> of <pages>", "page", page, "pages", pages);
    }

    /**
     * One label, in this server's words.
     *
     * <p>Plain text with the markup stripped, because the caller wraps it in its own colour tags and a
     * message file that added its own would end up with two sets nested inside each other.
     *
     * <p>Static, and looks the plugin up each time rather than holding a reference: this class is
     * static utility called from every menu in every plugin, and there is no moment at which it could
     * be handed a {@code Messages} that would still be the right one later.
     */
    private static String word(String key, String builtIn, Object... values) {
        if (!RainsCore.isAvailable()) {
            return builtIn;
        }
        Messages words = RainsCore.get().messages();
        if (words == null) {
            return builtIn;
        }
        return PlainTextComponentSerializer.plainText()
                .serialize(words.get(key, values));
    }

    public static ItemStack pageCounter(int page, int pages) {
        return of(Material.PAPER, "<" + Style.itemName() + ">Page " + page + " of " + pages);
    }

    /**
     * An item's name, styled the way every item name on this server is styled.
     *
     * <p>Public because plugins build item stacks that are not menu buttons — a custom item, a fence block, a
     * marking tool — and they should read the same. The alternative is each of them remembering to switch
     * vanilla's italics off, and the one that forgets has an item that looks like a mistake.
     */
    public static Component name(String miniMessage) {
        return text(miniMessage == null ? "" : miniMessage, Style.itemName());
    }

    /** One line of lore, likewise. */
    public static Component loreLine(String miniMessage) {
        return text(miniMessage == null ? "" : miniMessage, Style.itemLore());
    }

    /** MiniMessage with the vanilla italics explicitly switched off. See the class note. */
    private static Component text(String miniMessage, String fallbackColour) {
        String source = miniMessage.startsWith("<") ? miniMessage
                : "<" + fallbackColour + ">" + miniMessage;
        return MINI.deserialize(source).decoration(TextDecoration.ITALIC, false);
    }
}
