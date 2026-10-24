package de.raindancer.core.ui.menu;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * A painted window: a font glyph the size of the chest window, drawn by the window title, with the title itself
 * written after it in a colour that reads on the painting.
 *
 * <p>{@code glyphs} is the whole run in {@code font}: a negative space back to the window's left edge, the
 * painting, and a negative space back to where the title starts. The painting covers the player's inventory too,
 * so it has to leave a light patch where the game writes "Inventory" in its fixed dark grey.
 *
 * @param ink       the title's colour; parts of a title that bring their own colour keep it
 * @param titleFont what the title is written in — the game's font unless a backdrop asks for {@link #TITLE_FONT}
 */
public record Backdrop(Key font, String glyphs, TextColor ink, Key titleFont) {

    public static final Key FONT = Key.key(MenuStyle.NAMESPACE, "menu");
    /** Storybook small capitals for titles that want them; anything it lacks falls back to the game's font. */
    public static final Key TITLE_FONT = Key.key(MenuStyle.NAMESPACE, "title");
    public static final Key DEFAULT_FONT = Key.key("minecraft", "default");

    public Backdrop(Key font, String glyphs, TextColor ink) {
        this(font, glyphs, ink, DEFAULT_FONT);
    }

    public Backdrop writtenIn(Key titleFont) {
        return new Backdrop(font, glyphs, ink, titleFont);
    }
    /** Back from the title's start (x 8) to the window's edge, and from the end of a 176-wide painting to x 8. */
    private static final String LEAD = "";
    private static final String RETURN = "";

    private static final TextColor HOUSE_INK = TextColor.color(0xF4E4C1);
    private static final TextColor WORKSHOP_INK = TextColor.color(0xDCE8EE);

    /** The server's oak-and-brass cabinet, for every menu that does not choose otherwise. */
    public static Backdrop house(int rows) {
        return new Backdrop(FONT, LEAD + (char) (0xE000 + clamp(rows)) + RETURN, HOUSE_INK);
    }

    /** Riveted steel, for the screens only staff open. */
    public static Backdrop workshop(int rows) {
        return new Backdrop(FONT, LEAD + (char) (0xE010 + clamp(rows)) + RETURN, WORKSHOP_INK);
    }

    private static int clamp(int rows) {
        return Math.max(1, Math.min(6, rows));
    }

    public Component frame(Component title) {
        return Component.text(glyphs).font(font).color(NamedTextColor.WHITE)
                .append(legible(title.font(titleFont).colorIfAbsent(ink)));
    }

    /**
     * Titles were coloured for vanilla's light grey window — {@code <dark_gray>Shop} vanishes on dark wood. A part
     * whose colour sits on the painting's side of light and dark takes the ink instead; the rest keep theirs.
     */
    private Component legible(Component part) {
        TextColor colour = part.color();
        if (colour != null && clashes(colour)) {
            part = part.color(ink);
        }
        java.util.List<Component> children = part.children();
        // A gradient is a run of coloured siblings: if any of it clashes, all of it takes the ink.
        boolean anyClashes = children.stream().anyMatch(child -> child.color() != null && clashes(child.color()));
        return part.children(children.stream()
                .map(child -> anyClashes && child.color() != null ? child.color(ink) : child)
                .map(this::legible).toList());
    }

    private boolean clashes(TextColor colour) {
        return luminance(ink) > 0.5 ? luminance(colour) < 0.35 : luminance(colour) > 0.6;
    }

    private static double luminance(TextColor colour) {
        return (0.299 * colour.red() + 0.587 * colour.green() + 0.114 * colour.blue()) / 255;
    }
}
