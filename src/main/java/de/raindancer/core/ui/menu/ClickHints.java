package de.raindancer.core.ui.menu;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "Click to rename" in a button's lore shown as a mouse with its left button lit, "Shift right click" as a Shift key
 * and the right button — for whoever can draw them, rewritten as the button is placed ({@link Icons#forViewer}), so no
 * plugin writes its hints any differently.
 *
 * <p>Java draws them from {@link #FONT} in Core's pack. Bedrock players come through Geyser, which drops a component's
 * font: they get the same characters bare, and Bedrock finds them on its glyph page {@code glyph_F8} in Core's
 * Bedrock pack ({@link #writeBedrockPack}), which goes into Geyser's {@code packs} folder. Icons by Kenney (CC0).
 */
public final class ClickHints {

    public static final Key FONT = Key.key(MenuStyle.NAMESPACE, "hints");
    /** The high byte of every glyph: Bedrock's page {@code font/glyph_F8.png}, cell = the low byte. */
    public static final String BEDROCK_PAGE = "F8";
    /**
     * Bedrock's cells are square and the Shift key is twice as wide: its right half is a cell of its own, sent right
     * after {@link Glyph#SHIFT}.
     */
    public static final char BEDROCK_SHIFT_TAIL = '\uF804';
    static final String BEDROCK_ROOT = "/de/raindancer/core/bedrock/";
    private static final List<String> BEDROCK_FILES = List.of(
            "manifest.json", "pack_icon.png", "font/glyph_" + BEDROCK_PAGE + ".png");

    public enum Glyph {
        SHIFT('', "shift"),
        LEFT('', "left"),
        RIGHT('', "right"),
        MIDDLE('', "middle");

        private final char character;
        private final String texture;

        Glyph(char character, String texture) {
            this.character = character;
            this.texture = texture;
        }

        public char character() {
            return character;
        }

        /** The Java texture's name under {@code textures/font/hints/}. */
        public String texture() {
            return texture;
        }

        public static Glyph of(char character) {
            for (Glyph glyph : values()) {
                if (glyph.character == character) {
                    return glyph;
                }
            }
            return null;
        }
    }

    public enum Audience { NONE, JAVA, BEDROCK }

    /*
     * A hint is "Click" with a capital (lower case is prose: "a click opens it"), or a button or Shift named with it,
     * any case, by space or hyphen. Never part of a longer word: "Clicked", "doubleClick", "Click-through".
     */
    private static final Pattern HINT = Pattern.compile(
            "(?<![\\p{L}\\p{N}_-])(?:"
                    + "(?i:shift)[- ](?:(?i:left|right|middle)[- ])?(?i:click)"
                    + "|(?i:left|right|middle)[- ](?i:click)"
                    + "|Click"
                    + ")(?![\\p{L}\\p{N}_-])");

    private static final TextReplacementConfig JAVA_REPLACEMENT = replacement(Audience.JAVA);
    private static final TextReplacementConfig BEDROCK_REPLACEMENT = replacement(Audience.BEDROCK);

    private static volatile Function<UUID, Audience> who = id -> Audience.NONE;

    private ClickHints() {
    }

    public static void install(Function<UUID, Audience> audience) {
        who = audience == null ? id -> Audience.NONE : audience;
    }

    public static void uninstall() {
        who = id -> Audience.NONE;
    }

    public static Audience audience(UUID player) {
        if (player == null) {
            return Audience.NONE;
        }
        try {
            Audience audience = who.apply(player);
            return audience == null ? Audience.NONE : audience;
        } catch (RuntimeException unknown) {
            return Audience.NONE;
        }
    }

    /**
     * @param bedrock   a Floodgate player
     * @param wearing   has Core's Java pack on — which Geyser may report for a Bedrock client too, so it never makes
     *                  one a Java audience
     * @param javaOn    the server's setting for Java players
     * @param bedrockOn the server's setting for Bedrock players
     */
    public static Audience audience(boolean bedrock, boolean wearing, boolean javaOn, boolean bedrockOn) {
        if (bedrock) {
            return bedrockOn ? Audience.BEDROCK : Audience.NONE;
        }
        return javaOn && wearing ? Audience.JAVA : Audience.NONE;
    }

    /** Floodgate gives Bedrock players ids whose upper half is zero; no Java account has one. */
    public static boolean isBedrock(UUID id) {
        return id != null && id.getMostSignificantBits() == 0;
    }

    public static Component rewrite(Component line, Audience audience) {
        if (line == null || audience == null || audience == Audience.NONE) {
            return line;
        }
        Component shown = line.replaceText(audience == Audience.JAVA ? JAVA_REPLACEMENT : BEDROCK_REPLACEMENT);
        return shown.equals(line) ? line : shown;
    }

    /** The lore with its hints drawn; the very same list when there was nothing to draw. */
    public static List<Component> rewrite(List<Component> lore, Audience audience) {
        if (lore == null || audience == null || audience == Audience.NONE) {
            return lore;
        }
        List<Component> shown = new ArrayList<>(lore.size());
        boolean changed = false;
        for (Component line : lore) {
            Component drawn = rewrite(line, audience);
            changed |= drawn != line;
            shown.add(drawn);
        }
        return changed ? shown : lore;
    }

    /** Writes {@code rainscore.mcpack} into {@code folder}, over an older one; copy it into Geyser's {@code packs}. */
    public static Path writeBedrockPack(Path folder) throws IOException {
        Files.createDirectories(folder);
        Path target = folder.resolve("rainscore.mcpack");
        Path partial = Files.createTempFile(folder, "rainscore", ".mcpack.part");
        try {
            try (OutputStream out = Files.newOutputStream(partial); ZipOutputStream zip = new ZipOutputStream(out)) {
                for (String file : BEDROCK_FILES) {
                    try (InputStream in = ClickHints.class.getResourceAsStream(BEDROCK_ROOT + file)) {
                        if (in == null) {
                            throw new IOException("Core's Bedrock pack is missing " + file);
                        }
                        zip.putNextEntry(new ZipEntry(file));
                        in.transferTo(zip);
                        zip.closeEntry();
                    }
                }
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(partial);
        }
        return target;
    }

    private static TextReplacementConfig replacement(Audience audience) {
        return TextReplacementConfig.builder()
                .match(HINT)
                .replacement((MatchResult match, net.kyori.adventure.text.TextComponent.Builder ignored) ->
                        glyphs(match.group(), audience))
                .build();
    }

    private static Component glyphs(String hint, Audience audience) {
        String said = hint.toLowerCase(Locale.ROOT);
        Glyph button = said.contains("right") ? Glyph.RIGHT : said.contains("middle") ? Glyph.MIDDLE : Glyph.LEFT;
        if (!said.startsWith("shift")) {
            return glyph(button, audience);
        }
        return Component.textOfChildren(glyph(Glyph.SHIFT, audience), glyph(button, audience));
    }

    private static Component glyph(Glyph glyph, Audience audience) {
        String text = String.valueOf(glyph.character());
        if (glyph == Glyph.SHIFT && audience == Audience.BEDROCK) {
            text += BEDROCK_SHIFT_TAIL;
        }
        Component drawn = Component.text(text, NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, false);
        return audience == Audience.JAVA ? drawn.font(FONT) : drawn;
    }
}
