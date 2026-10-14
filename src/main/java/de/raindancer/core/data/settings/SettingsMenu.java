package de.raindancer.core.data.settings;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import de.raindancer.core.RainsCore;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.choose.ColorChooser;
import de.raindancer.core.ui.choose.ItemChooser;
import de.raindancer.core.ui.choose.OptionChooser;
import de.raindancer.core.ui.prompt.ChatPrompts;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * The settings, as a window.
 *
 * <h2>What this class is allowed to decide</h2>
 * Nothing. {@link SettingsNavigation} works out which page shows what, what the trail says and
 * whether a click can change a value; this turns that into buttons. Every rule worth a test lives on
 * the other side of that line, which is why there is no {@code SettingsMenuTest} — there would be
 * nothing in it but Bukkit.
 *
 * <h2>The shape of a page</h2>
 * Categories go in the bands, so a page of six subtopics reads as six doors rather than a grid.
 * Settings go in a grid, because a page of them is a list of equal things. A page holding both puts
 * the categories in the top band and the settings below, which is the order somebody reads in.
 */
public final class SettingsMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SettingsNavigation navigation;
    private final Chat chat;

    /**
     * Where the wording comes from.
     *
     * <p>Asked for rather than held, because this menu is built long before the plugin has finished
     * starting and holding a reference taken then would be a reference to nothing.
     */
    private static Messages words() {
        return RainsCore.get().messages();
    }

    private static ChatButtons buttons() {
        return RainsCore.get().buttons();
    }

    private static ChatPrompts prompts() {
        return RainsCore.get().prompts();
    }

    /**
     * The clickable half of the typed-value prompt — a real registered-command button through
     * {@link de.raindancer.core.ui.chat.ChatButtons}, the same mechanism a claim's Accept/Deny or a
     * TPA request already use, rather than a click that fakes a typed chat line.
     *
     * <h2>Why this replaced sending "cancel" as a bare {@code run_command}</h2>
     * A client now shows a "Confirm Command Execution" screen for any {@code run_command} click
     * whose text is not a command it recognises, slash or no slash — the old trick of sending the
     * word "cancel" as if typed by hand stopped being silent. A button backed by a callback runs an
     * actual registered command ({@code /rc <token>}), which the client already
     * knows about, so nothing intercepts the click — one press, no extra enter, exactly the button
     * every other yes/no prompt in this codebase already is.
     */
    private Component cancelButton() {
        return buttons().label("<white><underlined>cancel</underlined></white>")
                .tooltip("<gray>Click to leave it as it is")
                .forOnly(viewer.getUniqueId())
                .does(who -> prompts().offer(who, "cancel"))
                .render();
    }

    /** How many category buttons fit in the band — the seven columns between the frame panes. */
    private static final int TOPICS_PER_PAGE = 7;

    /** Settings go in the rows between the category band and the toolbar. */
    private static final int FIRST_SETTINGS_ROW = MenuLayout.RULES;
    private static final int SETTINGS_PER_PAGE = (MenuLayout.LAND - FIRST_SETTINGS_ROW + 1) * 9;

    private final String path;
    private final SettingsPage page;
    private int window;

    public SettingsMenu(Player viewer, Brand brand, Chat chat, SettingsNavigation navigation,
                        String path, Menu parent) {
        super(viewer, brand, parent == null ? pageAbove(viewer, brand, chat, navigation, path) : parent);
        this.navigation = navigation;
        this.chat = chat;
        this.path = path;
        this.page = navigation.page(path);
    }

    /**
     * The page one level up, built from the path alone — what Back goes to when nobody handed this
     * window a parent.
     *
     * <h2>Why this exists</h2>
     * Because a settings page can be opened without one, and then it had no Back button at all: the
     * chrome paints one only when {@code parent != null}, and {@link SettingsChatInput} reopens the
     * page somebody was on after they type a value with no parent to hand it. The result was a page
     * three levels into the tree with nothing on it but Close — reported as exactly that, "on this
     * page we're missing a back button". A page's parent is not a secret the opener has to remember;
     * it is in the path, so it is derived from the path.
     *
     * <p>Recursive, and it terminates: every step drops a segment, and the root has no path at all.
     * Building the chain is cheap — a {@link SettingsPage} is a lookup in a tree that is already in
     * memory, and nothing is drawn until somebody actually presses Back.
     */
    private static Menu pageAbove(Player viewer, Brand brand, Chat chat, SettingsNavigation navigation,
                                  String path) {
        if (path == null || path.isBlank()) {
            return null;   // the front page: there is nothing above it
        }
        return new SettingsMenu(viewer, brand, chat, navigation,
                navigation.page(path).parentPath(), null);
    }

    /** The front page. */
    public static SettingsMenu root(Player viewer, Brand brand, Chat chat,
                                    SettingsNavigation navigation) {
        return new SettingsMenu(viewer, brand, chat, navigation, null, null);
    }

    /**
     * Only this page's own name: {@link Menu#windowTitle()} already puts the page above in front of it.
     * The whole path used to be here, and a deep page — Economy ▸ Gambling ▸ Slot machine — had its end, the
     * one part that says where you are, clipped off by the client.
     */
    @Override
    protected Component title() {
        return MINI.deserialize("<gray>" + MINI.escapeTags(page.isRoot() ? "Settings" : page.title()));
    }

    @Override
    public String breadcrumb() {
        return page.isRoot() ? "the settings" : page.title();
    }

    @Override
    protected List<String> helpLines() {
        return page.isRoot()
                ? List.of("Everything every plugin on this server can be told to do.",
                        "Click a category to go in.")
                : List.of("Click a setting to change it.",
                        "Only free text is typed in chat.");
    }

    @Override
    protected void render() {
        window = MenuLayout.clampPage(window, windows());
        int column = 1;
        for (SettingsTopic topic : page.topicsOn(window, TOPICS_PER_PAGE)) {
            band(MenuLayout.WHO, column++, categoryIcon(topic), event -> open(topic.path()));
        }

        int index = 0;
        for (Setting<?> setting : page.settingsOn(window, SETTINGS_PER_PAGE)) {
            cell(FIRST_SETTINGS_ROW + index / 9, index % 9, settingIcon(setting), event -> onClick(setting));
            index++;
        }

        toolbar(4, Icons.of(Material.SPYGLASS, "<white>Search",
                        "<gray>Find a setting by a word in its name",
                        "<gray>or what it does, from every plugin."),
                event -> SettingsSearchMenu.ask(viewer, brand(), chat, navigation, this));
    }

    /**
     * More than seven categories, or more settings than two rows hold, used to be a wall this class
     * quietly built: everything past it was dropped with no error and no warning — random teleport went
     * missing from the front page the day it became the eighth module, and every casino game after the
     * eighteenth gambling setting could not be found at all. Paged instead, both together, the same way
     * {@link de.raindancer.core.ui.menu.PaginatedMenu} pages a list too long for one screen.
     */
    @Override
    protected void paintPagingChrome(int chromeRow) {
        int pages = windows();
        if (pages <= 1) {
            return;
        }
        if (window > 0) {
            set(chromeRow + MenuLayout.CHROME_PREVIOUS, Icons.previousPage(window, pages), turnTo(window - 1));
        }
        if (window < pages - 1) {
            set(chromeRow + MenuLayout.CHROME_NEXT, Icons.nextPage(window + 2, pages), turnTo(window + 1));
        }
        set(chromeRow + MenuLayout.CHROME_PAGE, Icons.pageCounter(window + 1, pages));
    }

    private int windows() {
        return page.pageCount(TOPICS_PER_PAGE, SETTINGS_PER_PAGE);
    }

    private Consumer<InventoryClickEvent> turnTo(int newPage) {
        return event -> {
            window = newPage;
            refresh();
        };
    }

    private ItemStack categoryIcon(SettingsTopic topic) {
        Material material = topic.icon() == Material.AIR ? Material.BOOK : topic.icon();
        if (material == Material.PLAYER_HEAD) {
            // The viewer's own face, not Steve's. "Your settings" over a default head is a menu that
            // has not noticed who is looking at it.
            return Icons.head(viewer(), "<white>" + topic.title(), navigation.describe(topic));
        }
        return Icons.of(material, "<white>" + topic.title(), navigation.describe(topic));
    }

    private ItemStack settingIcon(Setting<?> setting) {
        return Icons.of(materialFor(navigation, setting), "<white>" + setting.title(), navigation.describe(setting));
    }

    /** A setting's icon: its own, or for a flag a green or grey dye, so on and off show at a glance. */
    static Material materialFor(SettingsNavigation navigation, Setting<?> setting) {
        Material material = setting.icon() == null || setting.icon() == Material.AIR
                ? Material.PAPER : setting.icon();
        if (setting.type() == Boolean.class) {
            boolean on = "on".equals(navigation.registry().display(setting.key()));
            material = on ? Material.LIME_DYE : Material.GRAY_DYE;
        }
        return material;
    }

    /** Asked again when a choice lands: where the value comes from may have changed while the chooser was open. */
    private boolean mayStill(Setting<?> setting) {
        if (navigation.registry().mayChange(viewer, setting.key())) {
            return true;
        }
        chat.no(viewer, "<red>That is set for the whole network or a group of servers now; it was left as it was.");
        return false;
    }

    private void onClick(Setting<?> setting) {
        if (!navigation.registry().mayChange(viewer, setting.key())) {
            chat.raw(viewer, chat.prefixed("<red>That is set for the whole network or a group of servers; changing it "
                    + "there needs more than changing this server. <gray>/settings sethere <white><key></white> <value> "
                    + "changes it for this server only.", Chat.arg("key", setting.key())));
            return;
        }
        SettingsNavigation.Click what = navigation.click(setting.key());
        switch (what) {
            case CYCLED -> {
                SettingsSaving.saveThenTell(navigation.registry(), viewer);
                refresh();
            }
            case NEEDS_MATERIAL_CHOICE -> new ItemChooser(viewer, brand(), this,
                    "Choose " + setting.title(),
                    chosen -> {
                        if (!mayStill(setting)) {
                            return;
                        }
                        navigation.registry().set(setting.key(), chosen.name());
                        SettingsSaving.saveThenTell(navigation.registry(), viewer);
                        refresh();
                    }).open();
            case NEEDS_COLOR_CHOICE -> new ColorChooser(viewer, brand(), this,
                    setting.title(),
                    NamedTextColor.NAMES.value(
                            navigation.registry().display(setting.key())),
                    chosen -> {
                        if (!mayStill(setting)) {
                            return;
                        }
                        navigation.registry().set(setting.key(),
                                NamedTextColor.NAMES.key(chosen));
                        SettingsSaving.saveThenTell(navigation.registry(), viewer);
                        refresh();
                    }).open();
            case NEEDS_TYPING -> {
                // Typed in chat rather than in an anvil: an anvil cannot show what the value is now
                // or what it is allowed to be, and both matter more than not leaving the window.
                viewer.closeInventory();
                chat.raw(viewer, chat.prefixed("<gray>Type a new value for <white><name></white>, or ",
                                Chat.arg("name", setting.title()))
                        .append(cancelButton())
                        .append(Component.text(".")));
                chat.row(viewer, "<dark_gray>  now: <gray>"
                        + navigation.registry().display(setting.key()));
                if (setting.min() != null) {
                    chat.row(viewer, "<dark_gray>  from " + setting.min() + " to " + setting.max());
                }
                if (!SettingsChatInput.expect(viewer, setting.key(), path)) {
                    // Somebody else is already asking them something. Saying so beats quietly
                    // taking over the answer they were about to give to another plugin.
                    chat.raw(viewer, words().prefixed("settings.finish-first"));
                }
            }
            case NEEDS_OPTION_CHOICE -> new OptionChooser(viewer, brand(),
                    this, setting.title(), setting.choices(),
                    navigation.registry().display(setting.key()),
                    chosen -> {
                        if (!mayStill(setting)) {
                            return;
                        }
                        navigation.registry().set(setting.key(), chosen);
                        SettingsSaving.saveThenTell(navigation.registry(), viewer);
                        refresh();
                    }).open();
            case NEEDS_NUMBER_CHOICE -> new AmountChooser(viewer, brand(),
                    this, setting.title(), currentNumber(setting), setting.min(), setting.max(),
                    chosen -> {
                        if (!mayStill(setting)) {
                            return;
                        }
                        navigation.registry().set(setting.key(), String.valueOf(chosen));
                        SettingsSaving.saveThenTell(navigation.registry(), viewer);
                        refresh();
                    }).open();
            case UNKNOWN -> chat.raw(viewer, words().prefixed("settings.gone"));
        }
    }

    /**
     * Where the number picker opens: what the setting is now, or its lower bound when whatever is on
     * disk will not parse as a number. Never a guess like zero — a range that starts at 100 would
     * open outside itself, and {@code AmountChooser} would then have to explain a value nobody set.
     */
    private int currentNumber(Setting<?> setting) {
        try {
            return Integer.parseInt(navigation.registry().display(setting.key()).trim());
        } catch (NumberFormatException notANumber) {
            return setting.min();
        }
    }

    private void open(String childPath) {
        new SettingsMenu(viewer, brand(), chat, navigation, childPath, this).open();
    }

    /** Which page this is, so the chat-input listener can reopen where somebody left off. */
    public String path() {
        return path;
    }
}
