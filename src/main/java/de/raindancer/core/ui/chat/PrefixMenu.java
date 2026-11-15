package de.raindancer.core.ui.chat;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.choose.StyleEditor;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.ui.prompt.AnvilInput;
import de.raindancer.core.ui.prompt.Parsed;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * {@code /prefix}: how every plugin on the server signs its messages, changed by clicking and shown
 * on every plugin's very next line.
 */
public final class PrefixMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Shapes worth one click. Anything else is typed. */
    static final List<Shape> SHAPES = List.of(
            new Shape("Chevron", PrefixDesign.DEFAULT_FORMAT),
            new Shape("Brackets", "<dark_gray>[</dark_gray>{tag}<dark_gray>]</dark_gray> "),
            new Shape("Bar", "{tag} <dark_gray>|</dark_gray> "),
            new Shape("Colon", "{tag}<dark_gray>:</dark_gray> "),
            new Shape("Arrow", "{tag} <dark_gray>➜</dark_gray> "),
            new Shape("Guillemets", "<dark_gray>«</dark_gray>{tag}<dark_gray>»</dark_gray> "),
            new Shape("Tag and plugin", "{tag} <gray>{plugin}</gray> <dark_gray>»</dark_gray> "));

    record Shape(String name, String format) {
    }

    private final PrefixService service;

    public PrefixMenu(Player viewer, Brand brand, Menu parent, PrefixService service) {
        super(viewer, brand, parent);
        this.service = service;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">Prefix");
    }

    @Override
    public String breadcrumb() {
        return "Prefix";
    }

    @Override
    protected void render() {
        PrefixDesign design = service.design();
        boolean shared = design.mode() == PrefixDesign.Mode.SHARED;

        band(MenuLayout.WHO, 2, Icons.of(shared ? Material.BELL : Material.BOOKSHELF,
                        "<white>" + (shared ? "One tag for every plugin" : "Each plugin its own name"),
                        shared ? "<gray>Every message says <white>" + Text.literal(sharedTag(design))
                                : "<gray>Claims say Claims, homes say Homes.",
                        "", "<dark_gray>Click to switch to " + (shared ? "each plugin's own." : "one shared tag.")),
                click -> change(d -> d.withMode(shared ? PrefixDesign.Mode.PER_PLUGIN : PrefixDesign.Mode.SHARED)));

        band(MenuLayout.WHO, 4, preview(design));

        band(MenuLayout.WHO, 6, Icons.of(design.shown() ? Material.LIME_DYE : Material.GRAY_DYE,
                        design.shown() ? "<green>Prefix shown" : "<gray>No prefix at all",
                        "<gray>Off sends every message bare.", "",
                        "<dark_gray>Click to " + (design.shown() ? "switch it off." : "switch it on.")),
                click -> change(d -> d.withShown(!d.shown())));

        band(MenuLayout.RULES, 1, Icons.of(Material.NAME_TAG, "<white>Shared tag",
                        "<gray>Now: <white>" + Text.literal(sharedTag(design)),
                        "<gray>Used when one tag signs for everybody.", "", "<dark_gray>Click to type it."),
                click -> AnvilInput.open(viewer, "The shared tag", design.tag(), PrefixMenu::tag,
                        typed -> {
                            save(d -> d.withTag(typed));
                            open();
                        }, this::open));

        band(MenuLayout.RULES, 3, Icons.of(Material.BRUSH, "<white>Colours and decorations",
                        "<gray>Now: " + StyleEditor.describe(design.style()),
                        "<gray>Empty uses the theme's gradient in bold.", "", "<dark_gray>Click to paint it."),
                click -> StyleEditor.of(viewer, brand(), this)
                        .heading("Prefix colours")
                        .sample(() -> sharedTag(service.design()))
                        .current(() -> service.design().style())
                        .onChange(style -> save(d -> d.withStyle(style)))
                        .open());

        band(MenuLayout.RULES, 5, Icons.of(Material.ITEM_FRAME, "<white>Shape",
                        "<gray>What goes around the tag.", "<gray>Now: " + shapeName(design.format()), "",
                        "<dark_gray>Click to pick one or type your own."),
                click -> new ShapePicker(this).open());

        band(MenuLayout.RULES, 7, Icons.of(Material.CHEST, "<white>Each plugin",
                        "<gray>Give one plugin its own tag, colours,", "<gray>or no prefix at all.",
                        "<gray>" + design.plugins().size() + " with their own look.", "",
                        "<dark_gray>Click to see them."),
                click -> new PluginList(this).open());

        if (service.isFileBroken()) {
            band(MenuLayout.LAND, 4, Icons.of(Material.REDSTONE_TORCH, "<red>prefix.yml is broken",
                    "<gray>Changes show now but are not saved", "<gray>until the file is fixed and",
                    "<gray>/prefix reload is run."));
        }

        danger(Icons.of(Material.BARRIER, "<red>Back to the default",
                        "<gray>Every plugin signs with its own name", "<gray>in the theme's gradient again.", "",
                        "<dark_gray>Asks first."),
                click -> new ConfirmMenu(viewer, brand(), this, "<red>Reset the prefix?",
                        List.of("<gray>The shared tag, colours, shape and every", "<gray>plugin's own look are forgotten."),
                        () -> {
                            save(d -> PrefixDesign.DEFAULT);
                            open();
                        }).open());
    }

    private void change(UnaryOperator<PrefixDesign> edit) {
        save(edit);
        refresh();
    }

    /** Saves without redrawing this page — for the pages opened from it, which redraw themselves. */
    private void save(UnaryOperator<PrefixDesign> edit) {
        if (!service.change(edit)) {
            viewer.sendMessage(Messages.spoken("prefix-design.not-saved",
                    "<red>prefix.yml could not be written, so this lasts until the next restart or reload."));
        }
    }

    private ItemStack preview(PrefixDesign design) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>What players see:");
        lore.add("");
        for (String plugin : samplePlugins()) {
            lore.add(Prefixes.chatPrefix(plugin, plugin) + "<white>Hello from " + Text.literal(plugin) + "!");
        }
        return Icons.of(Material.WRITABLE_BOOK, "<white>Preview", lore);
    }

    private static List<String> samplePlugins() {
        List<String> known = Prefixes.known();
        return known.isEmpty() ? List.of("Claims", "Homes") : known.subList(0, Math.min(3, known.size()));
    }

    private static String sharedTag(PrefixDesign design) {
        return design.tag().isEmpty() ? "(each plugin's own)" : design.tag();
    }

    private static String shapeName(String format) {
        for (Shape shape : SHAPES) {
            if (shape.format().equals(format)) {
                return shape.name();
            }
        }
        return "your own";
    }

    static Parsed<String> tag(String typed) {
        String cleaned = typed == null ? "" : typed.strip();
        if (cleaned.length() > 32) {
            return Parsed.no("At most 32 characters. It goes in front of every message.");
        }
        return Parsed.ok(cleaned);
    }

    static Parsed<String> format(String typed) {
        if (typed == null || typed.isBlank()) {
            return Parsed.no("Write something, with {tag} where the tag goes.");
        }
        if (!typed.contains(PrefixDesign.TAG)) {
            return Parsed.no("Put {tag} where the tag goes, like [{tag}].");
        }
        try {
            MINI.deserialize(typed.replace(PrefixDesign.TAG, "x").replace(PrefixDesign.PLUGIN, "x"));
        } catch (RuntimeException broken) {
            return Parsed.no("That markup does not read: " + broken.getMessage());
        }
        return Parsed.ok(typed.endsWith(" ") ? typed : typed + " ");
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Changes show on every plugin's next message.",
                "<gray>Also in plugins/RainsCore/prefix.yml.",
                "<gray>/prefix reload reads that file again.");
    }

    // ------------------------------------------------------------------ the shape page

    private final class ShapePicker extends PaginatedMenu<Shape> {

        ShapePicker(Menu parent) {
            super(PrefixMenu.this.viewer, PrefixMenu.this.brand(), parent);
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<" + Style.titleLabel() + ">Shape");
        }

        @Override
        protected List<Shape> entries() {
            List<Shape> all = new ArrayList<>(SHAPES);
            all.add(new Shape("Your own…", ""));
            return all;
        }

        @Override
        protected ItemStack icon(Shape shape) {
            if (shape.format().isEmpty()) {
                return Icons.of(Material.NAME_TAG, "<white>Your own…",
                        "<gray>MiniMessage with {tag} and, if you like, {plugin}.", "",
                        "<dark_gray>Click to type it.");
            }
            String example = shape.format().replace(PrefixDesign.PLUGIN, "Claims")
                    .replace(PrefixDesign.TAG, Prefixes.paintedTag("Claims", "Claims"));
            boolean now = shape.format().equals(service.design().format());
            return Icons.of(now ? Material.LIME_STAINED_GLASS_PANE : Material.PAPER,
                    "<white>" + shape.name() + (now ? " <green>✔" : ""),
                    example + "<white>Hello!", "", now ? "<gray>In use" : "<dark_gray>Click to use it.");
        }

        @Override
        protected void onClick(Shape shape, InventoryClickEvent event) {
            if (shape.format().isEmpty()) {
                AnvilInput.open(viewer, "Shape, with {tag}", service.design().format(), PrefixMenu::format,
                        typed -> {
                            PrefixMenu.this.save(d -> d.withFormat(typed));
                            PrefixMenu.this.open();
                        }, this::open);
                return;
            }
            PrefixMenu.this.save(d -> d.withFormat(shape.format()));
            backToWhoeverOpenedThis();
        }
    }

    // ------------------------------------------------------------------ one plugin at a time

    private final class PluginList extends PaginatedMenu<String> {

        PluginList(Menu parent) {
            super(PrefixMenu.this.viewer, PrefixMenu.this.brand(), parent);
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<" + Style.titleLabel() + ">Each plugin");
        }

        @Override
        protected List<String> entries() {
            return Prefixes.known();
        }

        @Override
        protected ItemStack emptyIcon() {
            return Icons.of(Material.COBWEB, "<gray>No plugin has spoken yet");
        }

        @Override
        protected ItemStack icon(String plugin) {
            PrefixDesign.PluginPrefix own = service.design().pluginPrefix(plugin);
            return Icons.of(own.isEmpty() ? Material.BOOK : Material.ENCHANTED_BOOK,
                    "<white>" + Text.literal(plugin),
                    Prefixes.chatPrefix(plugin, plugin) + "<white>Hello!",
                    own.isEmpty() ? "<gray>Looks like everybody else." : "<gray>Has its own look.",
                    "", "<dark_gray>Click to change it.");
        }

        @Override
        protected void onClick(String plugin, InventoryClickEvent event) {
            new PluginPage(this, plugin).open();
        }
    }

    private final class PluginPage extends Menu {

        private final String plugin;

        PluginPage(Menu parent, String plugin) {
            super(PrefixMenu.this.viewer, PrefixMenu.this.brand(), parent);
            this.plugin = plugin;
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<" + Style.titleLabel() + ">" + Text.literal(plugin));
        }

        @Override
        public String breadcrumb() {
            return plugin;
        }

        private PrefixDesign.PluginPrefix own() {
            return service.design().pluginPrefix(plugin);
        }

        private void edit(UnaryOperator<PrefixDesign.PluginPrefix> change) {
            PrefixMenu.this.save(d -> d.withPlugin(plugin, change.apply(d.pluginPrefix(plugin))));
            refresh();
        }

        @Override
        protected void render() {
            PrefixDesign.PluginPrefix own = own();
            boolean shared = service.design().mode() == PrefixDesign.Mode.SHARED;

            band(MenuLayout.WHO, 4, Icons.of(Material.WRITABLE_BOOK, "<white>Preview",
                    Prefixes.chatPrefix(plugin, plugin) + "<white>Hello!",
                    shared ? "<yellow>The shared tag is on, so its own tag" : "",
                    shared ? "<yellow>and colours only show per plugin." : ""));

            band(MenuLayout.RULES, 2, Icons.of(Material.NAME_TAG, "<white>Own tag",
                            "<gray>Now: <white>" + Text.literal(own.tag().isEmpty() ? plugin : own.tag()), "",
                            "<dark_gray>Click to type it; empty for its own name."),
                    click -> AnvilInput.open(viewer, "Tag for " + plugin, own.tag(), PrefixMenu::tag,
                            typed -> {
                                edit(p -> new PrefixDesign.PluginPrefix(typed, p.style(), p.shown()));
                                open();
                            }, this::open));

            band(MenuLayout.RULES, 4, Icons.of(Material.BRUSH, "<white>Own colours",
                            "<gray>Now: " + (own.style().isEmpty() ? "the shared ones"
                                    : StyleEditor.describe(own.style())), "", "<dark_gray>Click to paint it."),
                    click -> StyleEditor.of(viewer, brand(), this)
                            .heading(plugin + " colours")
                            .sample(() -> own().tag().isEmpty() ? plugin : own().tag())
                            .current(() -> own().style())
                            .onChange(style -> PrefixMenu.this.save(d -> d.withPlugin(plugin,
                                    new PrefixDesign.PluginPrefix(d.pluginPrefix(plugin).tag(), style,
                                            d.pluginPrefix(plugin).shown()))))
                            .open());

            band(MenuLayout.RULES, 6, Icons.of(own.shown() ? Material.LIME_DYE : Material.GRAY_DYE,
                            own.shown() ? "<green>Prefix shown" : "<gray>No prefix for " + Text.literal(plugin),
                            "", "<dark_gray>Click to " + (own.shown() ? "switch it off." : "switch it on.")),
                    click -> edit(p -> new PrefixDesign.PluginPrefix(p.tag(), p.style(), !p.shown())));

            if (!own.isEmpty()) {
                danger(Icons.of(Material.BARRIER, "<red>Like everybody else",
                                "<gray>Forgets this plugin's own look.", "", "<dark_gray>Asks first."),
                        click -> new ConfirmMenu(viewer, brand(), this, "<red>Forget its own look?",
                                List.of("<gray>" + Text.literal(plugin) + " signs like every other plugin again."),
                                () -> {
                                    PrefixMenu.this.save(d -> d.withPlugin(plugin, null));
                                    open();
                                }).open());
            }
        }
    }
}
