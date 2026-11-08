package de.raindancer.core.ui.menu;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
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
import java.util.regex.Matcher;
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
            "(?<![\\p{L}\\p{N}_-])(?<hint>"
                    + "(?i:shift)[- ](?:(?i:left|right|middle)[- ])?(?i:click)"
                    + "|(?i:left|right|middle)[- ](?i:click)"
                    + "|Click"
                    + ")(?![\\p{L}\\p{N}_-])"
                    // "Click to open", "Right click: open" read as "[mouse] | open"
                    + "(?<join>\\s+to\\s+|\\s*:\\s+)?");

    /** Stands in for a piece that is not plain text, so no hint is ever matched into or across it. */
    private static final char OPAQUE = '\uFFFC';

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

    /**
     * The line with its hints drawn. The result is the line's pieces side by side under an empty root, each with its
     * whole style written out: a glyph nested over the rest of the line would hand its font on to it.
     */
    public static Component rewrite(Component line, Audience audience) {
        if (line == null || audience == null || audience == Audience.NONE) {
            return line;
        }
        List<Run> runs = new ArrayList<>();
        flatten(line, Style.empty(), runs);
        StringBuilder all = new StringBuilder();
        for (Run run : runs) {
            all.append(run.text() == null ? String.valueOf(OPAQUE) : run.text());
        }
        Matcher matcher = HINT.matcher(all);
        List<Component> out = new ArrayList<>();
        boolean changed = false;
        int at = 0;
        while (matcher.find()) {
            changed = true;
            copy(runs, at, matcher.start(), out, audience);
            Style there = styleAt(runs, matcher.start());
            out.addAll(glyphs(matcher.group("hint"), there, audience));
            if (matcher.group("join") != null) {
                Style gap = there.decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false);
                out.add(Component.text(" ", gap));
                out.add(Component.text("|", gap.color(NamedTextColor.DARK_GRAY)));
                out.add(Component.text(" ", gap));
            }
            at = matcher.end();
        }
        if (!changed && runs.stream().noneMatch(run -> hoverChanges(run.style(), audience))) {
            return line;
        }
        copy(runs, at, all.length(), out, audience);
        return Component.textOfChildren(out.toArray(new Component[0]));
    }

    /** A stretch of the line in one style: plain text, or a piece that is not ({@code text == null}). */
    private record Run(String text, Style style, Component opaque) {
    }

    private static void flatten(Component component, Style inherited, List<Run> out) {
        Style style = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent text) {
            if (!text.content().isEmpty()) {
                out.add(new Run(text.content(), style, null));
            }
        } else {
            out.add(new Run(null, style, component.children(List.of()).style(style)));
        }
        for (Component child : component.children()) {
            flatten(child, style, out);
        }
    }

    /** The runs' text between two positions of the joined line, each piece in its own run's style. */
    private static void copy(List<Run> runs, int from, int to, List<Component> out, Audience audience) {
        int start = 0;
        for (Run run : runs) {
            int length = run.text() == null ? 1 : run.text().length();
            int end = start + length;
            int a = Math.max(from, start);
            int b = Math.min(to, end);
            if (a < b) {
                Style style = withHover(run.style(), audience);
                out.add(run.text() == null ? run.opaque().style(style)
                        : Component.text(run.text().substring(a - start, b - start), style));
            }
            start = end;
            if (start >= to) {
                break;
            }
        }
    }

    private static Style styleAt(List<Run> runs, int position) {
        int start = 0;
        for (Run run : runs) {
            int end = start + (run.text() == null ? 1 : run.text().length());
            if (position < end) {
                return run.style();
            }
            start = end;
        }
        return Style.empty();
    }

    /** A hover's text has its hints drawn too: "Click to accept" over a chat button. */
    private static Style withHover(Style style, Audience audience) {
        HoverEvent<?> hover = style.hoverEvent();
        if (hover == null || hover.action() != HoverEvent.Action.SHOW_TEXT) {
            return style;
        }
        Component text = (Component) hover.value();
        Component drawn = rewrite(text, audience);
        return drawn == text ? style : style.hoverEvent(HoverEvent.showText(drawn));
    }

    private static boolean hoverChanges(Style style, Audience audience) {
        return withHover(style, audience) != style;
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

    /** A copy of the item with its lore's hints drawn; the very same item when there were none. */
    public static org.bukkit.inventory.ItemStack rewrite(org.bukkit.inventory.ItemStack item, Audience audience) {
        if (item == null || audience == null || audience == Audience.NONE || !item.hasItemMeta()) {
            return item;
        }
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.lore();
        List<Component> drawn = rewrite(lore, audience);
        if (drawn == lore) {
            return item;
        }
        org.bukkit.inventory.ItemStack copy = item.clone();
        meta.lore(drawn);
        copy.setItemMeta(meta);
        return copy;
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

    private static List<Component> glyphs(String hint, Style there, Audience audience) {
        String said = hint.toLowerCase(Locale.ROOT);
        Glyph button = said.contains("right") ? Glyph.RIGHT : said.contains("middle") ? Glyph.MIDDLE : Glyph.LEFT;
        if (!said.startsWith("shift")) {
            return List.of(glyph(button, there, audience));
        }
        return List.of(glyph(Glyph.SHIFT, there, audience), glyph(button, there, audience));
    }

    /** In the style where the hint stood — a chat button's click and hover stay on it — but white, upright, plain. */
    private static Component glyph(Glyph glyph, Style there, Audience audience) {
        String text = String.valueOf(glyph.character());
        if (glyph == Glyph.SHIFT && audience == Audience.BEDROCK) {
            text += BEDROCK_SHIFT_TAIL;
        }
        Style style = withHover(there, audience).color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, false)
                .font(audience == Audience.JAVA ? FONT : null);
        return Component.text(text, style);
    }
}
