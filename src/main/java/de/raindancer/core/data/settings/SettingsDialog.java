package de.raindancer.core.data.settings;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.choose.ColorChooser;
import de.raindancer.core.ui.choose.ItemChooser;
import de.raindancer.core.ui.dialog.DialogAnswers;
import de.raindancer.core.ui.dialog.DialogPage;
import de.raindancer.core.ui.dialog.DialogReturn;
import de.raindancer.core.ui.identity.Symbols;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings as the game's own window, page for page what {@link SettingsMenu} showed: a door per plugin, its
 * sections, and each setting as a button saying its value. A switch flips where it is; a number or a text is typed
 * into the window; one of a named set or a list's switches are a button each. Only a block or a colour is still picked
 * on a chest page, out of Core's choosers, whose Back brings the window back.
 *
 * <p>Like the menu it decides nothing: {@link SettingsNavigation} knows what each page holds and how a value
 * changes, this only draws it.
 */
public final class SettingsDialog {

    /** The one text field of an edit or search window. */
    static final String VALUE = "value";

    /** A setting's button, wide enough for a long name and its value. */
    private static final int WIDE = 310;

    private static final String HEADING = "<gradient:#7DD3FC:#A5B4FC>Settings</gradient>";
    private static final String NOT_HERE = "<red>That is set for the whole network or a group of servers; changing it"
            + " there needs more than changing this server. /settings sethere changes it for this server only.";

    private final Player viewer;
    private final SettingsNavigation navigation;
    private final Brand brand;
    private final Runnable exit;

    /**
     * @param exit where Back on the front page goes — the page the settings were opened from; null only closes
     */
    public SettingsDialog(Player viewer, SettingsNavigation navigation, Brand brand, Runnable exit) {
        this.viewer = viewer;
        this.navigation = navigation;
        this.brand = brand == null ? new Brand("Settings") : brand;
        this.exit = exit;
    }

    /** Shows the page at {@code path}; null for the front page. */
    public void open(String path) {
        page(path).show(viewer);
    }

    // ------------------------------------------------------------------ pages

    DialogPage page(String path) {
        SettingsPage page = navigation.page(path);
        // Settings carry their value in the label and need the whole width; a page of doors only fits two a row.
        boolean wide = !page.settings().isEmpty();
        DialogPage window = DialogPage.titled(HEADING, page.isRoot() ? new String[0]
                : page.trail().toArray(String[]::new)).columns(wide ? 1 : 2);
        if (page.isRoot()) {
            window.text("<gray>A door for every plugin with something to set. A setting in <gold>gold</gold> differs"
                    + " from its default.");
        } else if (page.isEmpty()) {
            window.text("<gray>Nothing to set here.");
        }
        for (SettingsTopic topic : page.subtopics()) {
            String to = page.pathTo(topic);
            String label = "<white>" + Text.literal(topic.title()) + " ›";
            String tooltip = withoutClickLines(navigation.describe(topic)) + "\n\n<dark_gray>» <white>Click to open it";
            if (wide) {
                window.button(WIDE, label, tooltip, answers -> open(to));
            } else {
                window.button(label, tooltip, answers -> open(to));
            }
        }
        settings(window, page.settings(), page.path());
        if (page.isRoot()) {
            int changed = navigation.changed().size();
            window.button("<white>Changed settings", (changed == 0 ? "<gray>Everything is at its default."
                    : "<gray>" + changed + (changed == 1 ? " differs" : " differ") + " from their default.")
                    + "\n\n<dark_gray>» <white>Click to see them", answers -> open(SettingsNavigation.CHANGED));
            window.button("<white>Search", "<gray>Find a setting by a word in its name or in what it does, from every"
                    + " plugin.\n\n<dark_gray>» <white>Click to search", answers -> searchAsk().show(viewer));
            window.button("<white>By command", "<gray>Everything here works as /settings too."
                    + "\n\n<dark_gray>» <white>Click for how", answers -> {
                        viewer.closeDialog();
                        viewer.performCommand("settings help");
                    });
            return exit == null ? window : window.back(exit);
        }
        String parent = page.parentPath();
        return window.back(() -> open(parent));
    }

    /** A button per setting, its value in the label; {@code from} is the page to come back to. */
    private void settings(DialogPage window, List<Setting<?>> settings, String from) {
        for (Setting<?> setting : settings) {
            window.button(WIDE, label(setting), tooltip(setting), answers -> press(setting, from));
        }
    }

    private String label(Setting<?> setting) {
        String now = navigation.now(setting);
        String name = (navigation.isChanged(setting) ? "<gold>" : "<white>") + Text.literal(setting.title());
        if (setting.type() != List.class && !now.isBlank() && now.length() <= 20) {
            name += " <dark_gray>» " + ("on".equals(now) ? "<green>" : "off".equals(now) ? "<red>" : "<aqua>")
                    + Text.literal(now);
        }
        return name;
    }

    /** What the chest page's lore said, its own click lines swapped for this window's one. */
    private String tooltip(Setting<?> setting) {
        return about(setting) + "\n\n<dark_gray>» <white>" + hint(setting);
    }

    /**
     * What a setting is and what it is now. The chest page's lines wrap its description to fit a lore; a window wraps
     * text itself, and wrapping it twice broke it into stubs, so the description goes in whole.
     */
    private String about(Setting<?> setting) {
        List<String> lines = new ArrayList<>(List.of(withoutClickLines(navigation.describe(setting)).split("\n", -1)));
        String description = setting.description() == null ? "" : setting.description().strip();
        if (!description.isEmpty()) {
            int blank = lines.indexOf("");
            lines = new ArrayList<>(blank < 0 ? List.of() : lines.subList(blank + 1, lines.size()));
            lines.addFirst("");
            lines.addFirst("<gray>" + description);   // as the chest page shows it: an author's own words, not a player's
        }
        return String.join("\n", lines);
    }

    /** The chest page's lines without its own "click" and "right-click" lines, which this window says its own way. */
    private static String withoutClickLines(List<String> described) {
        List<String> lines = new ArrayList<>(described);
        lines.removeIf(line -> line.startsWith("<yellow>" + Symbols.ARROW));
        while (!lines.isEmpty() && lines.getLast().isBlank()) {
            lines.removeLast();
        }
        return String.join("\n", lines);
    }

    private String hint(Setting<?> setting) {
        if (navigation.canCycle(setting)) {
            return "Click to switch " + ("on".equals(navigation.now(setting)) ? "off" : "on");
        }
        if (navigation.hasToggles(setting)) {
            return "Click to switch them on and off";
        }
        if (navigation.hasOptions(setting) || setting.type() == Material.class
                || setting.type() == NamedTextColor.class) {
            return "Click to choose";
        }
        return "Click to change it";
    }

    // ------------------------------------------------------------------ changing one

    private void press(Setting<?> setting, String from) {
        if (!navigation.registry().mayChange(viewer, setting.key())) {
            page(from).withNotice(NOT_HERE).show(viewer);
            return;
        }
        switch (navigation.click(setting.key())) {
            case CYCLED -> {
                saved();
                page(from).withNotice("<green>" + Text.literal(setting.title()) + " is now "
                        + Text.literal(navigation.now(setting)) + ".").show(viewer);
            }
            case NEEDS_TYPING, NEEDS_NUMBER_CHOICE -> typing(setting, from).show(viewer);
            case NEEDS_OPTION_CHOICE -> options(setting, from).show(viewer);
            case NEEDS_TOGGLES -> toggles(setting, from).show(viewer);
            case NEEDS_MATERIAL_CHOICE -> new ItemChooser(viewer, brand, returning(from), "Choose " + setting.title(),
                    chosen -> chosen(setting, chosen.name(), from)).open();
            case NEEDS_COLOR_CHOICE -> new ColorChooser(viewer, brand, returning(from), setting.title(),
                    NamedTextColor.NAMES.value(navigation.registry().display(setting.key())),
                    chosen -> chosen(setting, NamedTextColor.NAMES.key(chosen), from)).open();
            case UNKNOWN -> page(from).withNotice("<red>That setting is gone; its plugin may have stopped.").show(viewer);
        }
    }

    /** One setting's own window: what it is, then how to change it. */
    private DialogPage editing(Setting<?> setting, String from) {
        return DialogPage.titled(HEADING, setting.title()).text(about(setting).strip());
    }

    DialogPage typing(Setting<?> setting, String from) {
        DialogPage window = editing(setting, from);
        String now = navigation.registry().display(setting.key());
        if (now.length() > 40) {
            window.area(VALUE, "<gray>New value", now, 2048, 4);
        } else {
            window.field(VALUE, "<gray>New value", now, 256);
        }
        window.button("<green>Save", "<gray>Takes what is typed above.\n\n<dark_gray>» <white>Click to save it",
                answers -> {
                    if (!navigation.registry().mayChange(viewer, setting.key())) {
                        page(from).withNotice(NOT_HERE).show(viewer);
                        return;
                    }
                    if (navigation.registry().set(setting.key(), answers.text(VALUE))) {
                        saved();
                        page(from).withNotice("<green>Saved: " + Text.literal(setting.title()) + " is now "
                                + Text.literal(navigation.registry().display(setting.key())) + ".").show(viewer);
                    } else {
                        typing(setting, from).withNotice("<red>" + Text.literal(answers.text(VALUE))
                                + " is not a value " + Text.literal(setting.title()) + " takes"
                                + (setting.min() != null ? ": from " + setting.min() + " to " + setting.max() : "")
                                + ". Nothing was changed.").show(viewer);
                    }
                });
        return withDefault(window, setting, from);
    }

    DialogPage options(Setting<?> setting, String from) {
        DialogPage window = editing(setting, from).columns(2);
        String now = navigation.registry().display(setting.key());
        for (String choice : setting.choices()) {
            boolean picked = choice.equalsIgnoreCase(now);
            window.button((picked ? "<green>" : "<white>") + Text.literal(choice),
                    (picked ? "<gray>What it is now." : "<gray>Makes it " + Text.literal(choice) + ".")
                            + "\n\n<dark_gray>» <white>Click to choose it",
                    answers -> chosen(setting, choice, from));
        }
        return withDefault(window, setting, from);
    }

    DialogPage toggles(Setting<?> setting, String from) {
        DialogPage window = editing(setting, from).columns(2);
        List<String> listed = navigation.listed(setting.key());
        for (String entry : setting.choices()) {
            boolean in = listed.stream().anyMatch(entry::equalsIgnoreCase);
            boolean on = setting.listedMeansOff() != in;
            window.button("<white>" + Text.literal(SettingsNavigation.readableEntry(entry)) + ": "
                            + (on ? "<green>on" : "<red>off"),
                    "<dark_gray>» <white>Click to switch it " + (on ? "off" : "on"), answers -> {
                        if (!navigation.registry().mayChange(viewer, setting.key())) {
                            page(from).withNotice(NOT_HERE).show(viewer);
                            return;
                        }
                        navigation.toggle(setting.key(), entry);
                        saved();
                        toggles(setting, from).show(viewer);
                    });
        }
        return withDefault(window, setting, from);
    }

    private DialogPage withDefault(DialogPage window, Setting<?> setting, String from) {
        if (navigation.isChanged(setting)) {
            window.button("<yellow>Put back the default", "<gray>What its plugin shipped with."
                    + "\n\n<dark_gray>» <white>Click to put it back", answers -> {
                        if (!navigation.registry().mayChange(viewer, setting.key())) {
                            page(from).withNotice(NOT_HERE).show(viewer);
                            return;
                        }
                        navigation.registry().reset(setting.key());
                        saved();
                        page(from).withNotice("<green>" + Text.literal(setting.title()) + " is back to its default.")
                                .show(viewer);
                    });
        }
        return window.back(() -> open(from));
    }

    private void chosen(Setting<?> setting, String value, String from) {
        if (!navigation.registry().mayChange(viewer, setting.key())) {
            page(from).withNotice(NOT_HERE).show(viewer);
            return;
        }
        navigation.registry().set(setting.key(), value);
        saved();
        page(from).withNotice("<green>" + Text.literal(setting.title()) + " is now "
                + Text.literal(navigation.registry().display(setting.key())) + ".").show(viewer);
    }

    private DialogReturn returning(String from) {
        return new DialogReturn(viewer, brand, "Settings", () -> open(from));
    }

    private void saved() {
        SettingsSaving.saveThenTell(navigation.registry(), viewer);
    }

    // ------------------------------------------------------------------ search

    DialogPage searchAsk() {
        return DialogPage.titled(HEADING, "Search")
                .text("<gray>A word from a setting's name or from what it does.")
                .field(VALUE, "<gray>Search for", "", 64)
                .button("<green>Search", "<gray>Every plugin's settings.\n\n<dark_gray>» <white>Click to search",
                        answers -> results(answers.text(VALUE)).show(viewer))
                .back(() -> open(null));
    }

    DialogPage results(String query) {
        List<Setting<?>> found = navigation.search(query);
        DialogPage window = DialogPage.titled(HEADING, "Search", query);
        window.text(found.isEmpty() ? "<gray>Nothing mentions <white>" + Text.literal(query) + "<gray>."
                : "<gray>" + found.size() + (found.size() == 1 ? " setting mentions " : " settings mention ")
                + "<white>" + Text.literal(query) + "<gray>.");
        for (Setting<?> setting : found) {
            window.button(WIDE, label(setting), tooltip(setting), answers -> press(setting, navigation.pathOf(setting)));
        }
        return window.back(() -> searchAsk().show(viewer));
    }
}
