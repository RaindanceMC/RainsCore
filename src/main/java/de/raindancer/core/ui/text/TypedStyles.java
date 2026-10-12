package de.raindancer.core.ui.text;

import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads a style somebody typed — {@code gold bold #ff0000 animated}: colours in the order typed become the stops of a
 * gradient, decorations and "animated" may stand anywhere among them. Only reads; who may wear what is the caller's.
 */
public final class TypedStyles {

    /** What people type to make a gradient move. */
    private static final Set<String> ANIMATED_WORDS = Set.of("animated", "flowing", "moving");

    /** @param problem why nothing was read; empty when it was */
    public record Read(Optional<NameStyle> style, String problem) {

        static Read ok(NameStyle style) {
            return new Read(Optional.of(style), "");
        }

        static Read no(String problem) {
            return new Read(Optional.empty(), problem);
        }
    }

    private TypedStyles() {
    }

    /**
     * @param extraColour asked first for every word — a server's own palette, where "red" means the dye's red; null
     *                    when it has no colour of that name
     */
    public static Read read(List<String> words, Function<String, TextColor> extraColour) {
        if (words == null || words.isEmpty()) {
            return Read.no("Name at least one colour — a chat colour or a #hex code.");
        }
        NameStyle style = NameStyle.NONE;
        boolean animated = false;
        for (String word : words) {
            if (ANIMATED_WORDS.contains(word.trim().toLowerCase(Locale.ROOT))) {
                animated = true;
                continue;
            }
            TextDecoration decoration = decorationOf(word);
            if (decoration != null) {
                style = style.with(decoration, true);
                continue;
            }
            TextColor colour = extraColour == null ? null : extraColour.apply(word);
            if (colour == null) {
                colour = NameStyle.colourOf(word);
            }
            if (colour == null) {
                return Read.no("'" + word + "' is not a colour or a decoration here.");
            }
            style = style.withStop(colour);
        }
        return Read.ok(style.animated(animated));
    }

    /** {@code bold}, and the two spellings people actually use for the long ones. */
    public static TextDecoration decorationOf(String word) {
        String cleaned = word.trim().toLowerCase(Locale.ROOT);
        return switch (cleaned) {
            case "underline" -> TextDecoration.UNDERLINED;
            case "strike" -> TextDecoration.STRIKETHROUGH;
            case "magic" -> TextDecoration.OBFUSCATED;
            default -> TextDecoration.NAMES.value(cleaned);
        };
    }
}
