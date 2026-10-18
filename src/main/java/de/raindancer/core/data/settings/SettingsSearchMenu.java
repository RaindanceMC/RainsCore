package de.raindancer.core.data.settings;

import de.raindancer.core.RainsCore;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.identity.Symbols;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.ui.prompt.AnvilInput;
import de.raindancer.core.ui.prompt.Parsers;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings that mention a word, from every plugin at once — for an owner who knows what they want
 * changed but not which category it hides in. Clicking one goes to its page, where it is changed the
 * usual way; Back comes here again.
 */
public final class SettingsSearchMenu extends PaginatedMenu<Setting<?>> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** Longest search a player can type into the anvil. */
    private static final int LONGEST_QUERY = 40;

    private final Chat chat;
    private final SettingsNavigation navigation;
    private final String query;

    public SettingsSearchMenu(Player viewer, Brand brand, Chat chat, SettingsNavigation navigation,
                              String query, Menu parent) {
        super(viewer, brand, parent);
        this.chat = chat;
        this.navigation = navigation;
        this.query = query == null ? "" : query.strip();
    }

    /**
     * Asks what to look for in an anvil, then shows what matches. Closing the anvil goes back to
     * {@code from}, or simply closes when there is nowhere to go back to.
     */
    public static void ask(Player viewer, Brand brand, Chat chat, SettingsNavigation navigation, Menu from) {
        String title = RainsCore.isAvailable() ? RainsCore.get().messages().raw("settings.search-title")
                : "Search the settings";
        AnvilInput.open(viewer, Text.plain(Text.styled(title)), "", Parsers.text(LONGEST_QUERY),
                query -> new SettingsSearchMenu(viewer, brand, chat, navigation, query, from).open(),
                () -> {
                    if (from != null) {
                        from.reopen();
                    }
                });
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<gray>Search: <white>" + Text.literal(query));
    }

    @Override
    public String breadcrumb() {
        return "the search for " + query;
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Every setting that mentions what you searched for.",
                "Click one to go to its page and change it there.");
    }

    @Override
    protected List<Setting<?>> entries() {
        return navigation.search(query);
    }

    @Override
    protected ItemStack icon(Setting<?> setting) {
        List<String> lore = new ArrayList<>(navigation.describe(setting));
        // The last line says how to change it on its own page; here a click goes there first.
        if (!lore.isEmpty()) {
            lore.removeLast();
        }
        String where = whereIs(setting);
        if (!where.isEmpty()) {
            lore.add("<dark_gray>in " + Text.literal(where));
        }
        lore.add("<yellow>" + Symbols.ARROW + " Click to go there");
        return SettingsMenu.settingIcon(navigation, setting, lore);
    }

    private String whereIs(Setting<?> setting) {
        return String.join(" " + Symbols.ARROW + " ", navigation.trailOf(setting));
    }

    @Override
    protected void onClick(Setting<?> setting, InventoryClickEvent event) {
        new SettingsMenu(viewer, brand(), chat, navigation, navigation.pathOf(setting), this).open();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing mentions " + Text.literal(query),
                "<gray>Try a shorter word, or one from the setting's name.",
                "", "<yellow>" + Symbols.ARROW + " Click to search again");
    }

    @Override
    protected void emptyAction(InventoryClickEvent event) {
        ask(viewer, brand(), chat, navigation, parent());
    }

    @Override
    protected void decorate() {
        toolbar(4, Icons.of(Material.SPYGLASS, "<white>Search again", "<gray>Look for something else"),
                event -> ask(viewer, brand(), chat, navigation, parent()));
        super.decorate();
    }
}
