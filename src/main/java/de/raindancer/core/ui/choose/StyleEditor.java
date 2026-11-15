package de.raindancer.core.ui.choose;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.ui.prompt.AnvilInput;
import de.raindancer.core.ui.prompt.Parsed;
import de.raindancer.core.ui.text.Gradients;
import de.raindancer.core.ui.text.NameStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Painting a piece of text: colour stops left to right, decorations, flowing — the one editor for a
 * player's name, a chat prefix, or anything else drawn as a {@link NameStyle}.
 *
 * <h2>Why Core's</h2>
 * It was cosmetics' colour mixer. Then the server prefix wanted exactly the same page, and a second
 * copy is the one that forgets the "flowing" toggle. Every change is applied on the click — the preview
 * is the real thing, and a stop that is not allowed is refused, with the reason, on the click that adds it.
 *
 * <pre>{@code
 * StyleEditor.of(player, brand, parent)
 *         .heading("Prefix colours")
 *         .sample("Rain")
 *         .current(() -> design.style())
 *         .onChange(style -> service.change(d -> d.withStyle(style)))
 *         .open();
 * }</pre>
 */
public final class StyleEditor extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    public static final int DEFAULT_MAX_STOPS = 8;

    private String heading = "Colours";
    private Supplier<String> sample = () -> "Sample";
    private Supplier<NameStyle> current = () -> NameStyle.NONE;
    private Consumer<NameStyle> onChange = style -> { };
    private StyleGrants grants = StyleGrants.ALL;
    private List<Swatch> palette = Swatch.defaults();
    private int maxStops = DEFAULT_MAX_STOPS;
    private boolean decorationsHere = true;

    private StyleEditor(Player viewer, Brand brand, Menu parent) {
        super(viewer, brand, parent);
    }

    public static StyleEditor of(Player viewer, Brand brand, Menu parent) {
        return new StyleEditor(viewer, brand, parent);
    }

    // ------------------------------------------------------------------ configuring

    public StyleEditor heading(String text) {
        this.heading = text == null || text.isBlank() ? heading : text;
        return this;
    }

    /** The text the preview paints — a name, a tag. Asked every render, so a renamed tag shows at once. */
    public StyleEditor sample(String text) {
        return sample(() -> text);
    }

    public StyleEditor sample(Supplier<String> text) {
        this.sample = Objects.requireNonNull(text, "sample");
        return this;
    }

    public StyleEditor current(Supplier<NameStyle> style) {
        this.current = Objects.requireNonNull(style, "current");
        return this;
    }

    /** Called with every change, on the viewer's thread. Must make {@link #current} return it afterwards. */
    public StyleEditor onChange(Consumer<NameStyle> change) {
        this.onChange = Objects.requireNonNull(change, "onChange");
        return this;
    }

    public StyleEditor grants(StyleGrants allowed) {
        this.grants = allowed == null ? StyleGrants.NOTHING : allowed;
        return this;
    }

    public StyleEditor palette(List<Swatch> swatches) {
        this.palette = swatches == null ? List.of() : List.copyOf(swatches);
        return this;
    }

    public StyleEditor maxStops(int stops) {
        this.maxStops = Math.max(1, stops);
        return this;
    }

    /** Leaves the decorations off this page, for a caller that already shows them on its own. */
    public StyleEditor withoutDecorations() {
        this.decorationsHere = false;
        return this;
    }

    // ------------------------------------------------------------------ the page

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">" + de.raindancer.core.ui.text.Text.literal(heading));
    }

    @Override
    public String breadcrumb() {
        return heading;
    }

    @Override
    protected void render() {
        NameStyle style = current.get();
        Optional<String> addRefusal = grants.refuseAddingStop(style, maxStops);
        Optional<String> typeRefusal = grants.refuseTypedColour(style, maxStops);

        band(MenuLayout.WHO, 1, addRefusal.isEmpty(),
                Icons.of(Material.LIME_DYE, "<green>Add a colour",
                        "<gray>From the palette, onto the end.", "", "<dark_gray>Click to pick."),
                addRefusal.orElse(""),
                click -> new SwatchPicker(this, colour -> wear(style.withStop(colour))).open());

        band(MenuLayout.WHO, 2, typeRefusal.isEmpty(),
                Icons.of(Material.NAME_TAG, "<green>Type a colour",
                        "<gray>Any colour as a hex code, like #8e2de2,", "<gray>or a name like gold.",
                        "", "<dark_gray>Click to type it."),
                typeRefusal.orElse(""),
                click -> AnvilInput.open(viewer, "A colour, like #8e2de2", "#", StyleEditor::colour,
                        colour -> {
                            wear(style.withStop(colour));
                            open();
                        },
                        this::open));

        band(MenuLayout.WHO, 4, preview(style));

        band(MenuLayout.WHO, 5, style.isGradient(),
                Icons.of(Material.COMPARATOR, "<white>Reverse", "<gray>Runs the gradient the other way."),
                "Needs two colours or more",
                click -> wear(style.reversed()));

        band(MenuLayout.WHO, 6, !style.colours().isEmpty(),
                Icons.of(Material.WATER_BUCKET, "<white>Clear the colours",
                        "<gray>Keeps bold, italic and the rest."),
                "No colours to clear",
                click -> wear(style.withoutColours()));

        boolean flowing = style.isAnimated();
        Optional<String> flowRefusal = flowing ? Optional.empty() : grants.refuseFlowing(style);
        band(MenuLayout.WHO, 7, flowRefusal.isEmpty(),
                Icons.of(flowing ? Material.LIME_DYE : Material.GRAY_DYE,
                        flowing ? "<green>Flowing" : "<white>Flowing",
                        "<gray>Lets the gradient drift along,", "<gray>round and round.", "",
                        flowing ? "<green>On. Click to hold it still." : "<gray>Off. Click to set it moving."),
                flowRefusal.orElse(""),
                click -> wear(style.animated(!flowing)));

        if (decorationsHere) {
            int column = 1;
            for (TextDecoration decoration : TextDecoration.values()) {
                boolean on = style.has(decoration);
                // Taking one off is always allowed — a right withdrawn must not trap somebody in it.
                Optional<String> refusal = on ? Optional.empty() : grants.refuseDecoration(decoration);
                band(MenuLayout.RULES, column, refusal.isEmpty(), decorationIcon(decoration, on),
                        refusal.orElse(""),
                        click -> wear(style.toggle(decoration)));
                column += column == 3 ? 2 : 1;
            }
        }

        for (int index = 0; index < style.colours().size() && index < 9; index++) {
            int stop = index;
            cell(MenuLayout.LAND, index, stopIcon(style.colours().get(index), index),
                    click -> wear(style.withoutStop(stop)));
        }

        if (!style.isEmpty()) {
            danger(Icons.of(Material.BARRIER, "<red>Start again",
                            "<gray>Takes every colour and decoration off.", "", "<dark_gray>Asks first."),
                    click -> new ConfirmMenu(viewer, brand(), this, "<red>Start again?",
                            List.of("<gray>Every colour and decoration is taken off."),
                            ConfirmMenu.CANNOT_BE_UNDONE,
                            () -> {
                                onChange.accept(NameStyle.NONE);
                                open();
                            }).open());
        }
    }

    private void wear(NameStyle changed) {
        onChange.accept(changed);
        refresh();
    }

    private ItemStack preview(NameStyle style) {
        String painted = MINI.serialize(Gradients.styled(sample.get(), style));
        return Icons.of(Material.OAK_SIGN, painted,
                "<gray>How it looks.",
                "",
                "<dark_gray>" + describe(style));
    }

    private ItemStack stopIcon(TextColor colour, int index) {
        Swatch swatch = swatchOf(colour);
        String name = (index + 1) + ". " + (swatch == null ? colour.asHexString() : swatch.label());
        return Icons.of(swatch == null ? Material.PAPER : swatch.icon(),
                MINI.serialize(Gradients.styled(name, NameStyle.NONE.withColour(colour))),
                "<dark_gray>" + colour.asHexString(), "", "<gray>Click to take it out.");
    }

    private Swatch swatchOf(TextColor colour) {
        for (Swatch swatch : palette) {
            if (swatch.colour().value() == colour.value()) {
                return swatch;
            }
        }
        return null;
    }

    private static ItemStack decorationIcon(TextDecoration decoration, boolean on) {
        String word = decoration.name().toLowerCase(Locale.ROOT);
        String label = Character.toUpperCase(word.charAt(0)) + word.substring(1);
        return Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE,
                (on ? "<green>" : "<gray>") + "<" + word + ">" + label,
                on ? "<green>On. Click to turn off." : "<gray>Off. Click to turn on.");
    }

    /** "a gradient of 3 colours, bold, flowing" — what a style is, in words. */
    public static String describe(NameStyle style) {
        if (style.isEmpty()) {
            return "plain";
        }
        StringBuilder words = new StringBuilder();
        int stops = style.colours().size();
        words.append(stops == 0 ? "no colour" : stops == 1 ? "one colour" : "a gradient of " + stops + " colours");
        for (TextDecoration decoration : style.decorations()) {
            words.append(", ").append(decoration.name().toLowerCase(Locale.ROOT));
        }
        if (style.isAnimated()) {
            words.append(", flowing");
        }
        return words.toString();
    }

    static Parsed<TextColor> colour(String typed) {
        TextColor colour = NameStyle.colourOf(typed == null ? null : typed.strip().replace(' ', '_'));
        return colour == null
                ? Parsed.no("A # and six digits or a-f, like #8e2de2, or a name like gold.")
                : Parsed.ok(colour);
    }

    @Override
    protected List<String> helpLines() {
        return List.of(
                "<gray>Add colours left to right; two or more make a gradient.",
                "<gray>Click a colour in the bottom row to take it out.",
                "<gray>Up to " + maxStops + " colours.");
    }

    // ------------------------------------------------------------------ the palette page

    /** The palette as swatches, each named in its own colour; picking one hands it back and goes back. */
    private static final class SwatchPicker extends PaginatedMenu<Swatch> {

        private final StyleEditor editor;
        private final Consumer<TextColor> chosen;

        SwatchPicker(StyleEditor editor, Consumer<TextColor> chosen) {
            super(editor.viewer(), editor.brand(), editor);
            this.editor = editor;
            this.chosen = chosen;
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<" + Style.titleLabel() + ">Pick a colour");
        }

        @Override
        public String breadcrumb() {
            return "Colour";
        }

        @Override
        protected List<Swatch> entries() {
            return editor.palette;
        }

        @Override
        protected ItemStack emptyIcon() {
            return Icons.of(Material.COBWEB, "<gray>The palette is empty");
        }

        @Override
        protected ItemStack icon(Swatch swatch) {
            String label = Character.toUpperCase(swatch.label().charAt(0)) + swatch.label().substring(1);
            return Icons.of(swatch.icon(),
                    MINI.serialize(Gradients.styled(label, NameStyle.NONE.withColour(swatch.colour()))),
                    "<dark_gray>" + swatch.colour().asHexString(), "", "<gray>Click to add it.");
        }

        @Override
        protected void onClick(Swatch swatch, InventoryClickEvent event) {
            chosen.accept(swatch.colour());
            backToWhoeverOpenedThis();
        }
    }
}
