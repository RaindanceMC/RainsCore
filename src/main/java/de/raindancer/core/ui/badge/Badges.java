package de.raindancer.core.ui.badge;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Rank tags drawn as badges ({@link BadgeArt}): made from the tag a plugin already has — its letters the label, its
 * colours the gradient — and shipped in the server's pack as one glyph each in {@link #FONT}.
 *
 * <p>Bedrock players come through Geyser, which drops fonts, and Bedrock's glyph cells are 16 pixels square: a badge's
 * characters are its Bedrock cells side by side on the glyph pages from {@code F9} on ({@link #bedrockPages}), and in
 * Java's font every one after the first takes no room at all. So the same text is a badge on both.
 *
 * <p>Characters are handed out in the order tags are registered: every server of a network running the same ranks
 * registers them in the same order and agrees on them.
 */
public final class Badges {

    public static final Key FONT = Key.key("rainscore", "badges");
    public static final int MAX_STOPS = 4;
    private static final char FIRST = '豈';
    private static final char LAST = '﻿';
    private static final String TEXTURES = "assets/rainscore/textures/font/badges/";
    private static final String FONT_FILE = "assets/rainscore/font/badges.json";

    private final Map<String, Badge> byTag = new LinkedHashMap<>();
    private final Map<Character, Badge> byFirst = new LinkedHashMap<>();
    private char next = FIRST;
    private volatile Runnable changed = () -> { };

    /** Told after a new badge was registered, outside the lock: the pack has something new to carry. */
    public void onChange(Runnable listener) {
        changed = listener == null ? () -> { } : listener;
    }

    /** The badge for a tag in MiniMessage; empty when the tag has no letter the badge font can draw. */
    public Optional<Component> register(String tag) {
        Optional<Component> badge;
        boolean made;
        synchronized (this) {
            made = !byTag.containsKey(tag);
            badge = make(tag);
        }
        if (made && badge.isPresent()) {
            changed.run();
        }
        return badge;
    }

    private Optional<Component> make(String tag) {
        Badge known = byTag.get(tag);
        if (known != null) {
            return Optional.of(component(known));
        }
        String label = label(tag);
        if (label.isEmpty()) {
            return Optional.empty();
        }
        List<TextColor> colours = colours(tag);
        int cells = (BadgeArt.bedrock(label, colours).getWidth() + 15) / 16;
        if (next + cells - 1 > LAST) {
            return Optional.empty();
        }
        StringBuilder characters = new StringBuilder();
        for (int i = 0; i < cells; i++) {
            characters.append(next++);
        }
        Badge badge = new Badge(label, colours, characters.toString(), cells, MiniMessage.miniMessage().deserialize(tag));
        byTag.put(tag, badge);
        byFirst.put(badge.characters().charAt(0), badge);
        return Optional.of(component(badge));
    }

    public synchronized List<Badge> all() {
        return List.copyOf(byTag.values());
    }

    private static Component component(Badge badge) {
        return Component.text(badge.characters(), Style.style(NamedTextColor.WHITE).font(FONT)
                .decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
                .shadowColor(ShadowColor.none()));
    }

    /** The tag's letters, upper case, as far as the badge font has them; brackets and symbols left out. */
    public static String label(String tag) {
        String plain = MiniMessage.miniMessage().stripTags(tag).toUpperCase(Locale.ROOT);
        StringBuilder kept = new StringBuilder();
        for (char c : plain.toCharArray()) {
            if (c != ' ' && BadgeArt.glyph(c) != null) {
                kept.append(c);
            } else if (c == ' ' && !kept.isEmpty() && kept.charAt(kept.length() - 1) != ' ') {
                kept.append(' ');
            }
        }
        return kept.toString().strip();
    }

    /** The colours the tag's characters are drawn in, in order — at most {@link #MAX_STOPS}, first and last kept. */
    public static List<TextColor> colours(String tag) {
        List<TextColor> seen = new ArrayList<>();
        collect(MiniMessage.miniMessage().deserialize(tag), Style.empty(), seen);
        if (seen.isEmpty()) {
            return List.of(NamedTextColor.GRAY);
        }
        if (seen.size() <= MAX_STOPS) {
            return List.copyOf(seen);
        }
        List<TextColor> picked = new ArrayList<>();
        for (int i = 0; i < MAX_STOPS; i++) {
            picked.add(seen.get((int) Math.round(i * (seen.size() - 1) / (double) (MAX_STOPS - 1))));
        }
        return picked;
    }

    private static void collect(Component component, Style inherited, List<TextColor> seen) {
        Style style = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent text && style.color() != null) {
            for (char c : text.content().toCharArray()) {
                if (!Character.isWhitespace(c) && (seen.isEmpty() || !seen.getLast().equals(style.color()))) {
                    seen.add(style.color());
                }
            }
        }
        for (Component child : component.children()) {
            collect(child, style, seen);
        }
    }

    /**
     * Writes the Java font and its pictures into a pack folder; false when they were already there. Pictures of
     * badges that are gone are removed with it.
     */
    public synchronized boolean writeJava(Path folder) throws IOException {
        JsonArray providers = new JsonArray();
        JsonObject spaces = new JsonObject();
        Map<String, BufferedImage> pictures = new LinkedHashMap<>();
        for (Badge badge : byTag.values()) {
            String name = "badge_" + Integer.toHexString(badge.characters().charAt(0)) + "_"
                    + Integer.toHexString((badge.label() + badge.colours()).hashCode()) + ".png";
            pictures.put(name, BadgeArt.java(badge.label(), badge.colours()));
            JsonObject bitmap = new JsonObject();
            bitmap.addProperty("type", "bitmap");
            bitmap.addProperty("file", "rainscore:font/badges/" + name);
            bitmap.addProperty("ascent", 8);
            bitmap.addProperty("height", 9);
            JsonArray chars = new JsonArray();
            chars.add(badge.characters().substring(0, 1));
            bitmap.add("chars", chars);
            providers.add(bitmap);
            for (char tail : badge.characters().substring(1).toCharArray()) {
                spaces.addProperty(String.valueOf(tail), 0);
            }
        }
        if (!spaces.isEmpty()) {
            JsonObject space = new JsonObject();
            space.addProperty("type", "space");
            space.add("advances", spaces);
            providers.add(space);
        }
        JsonObject font = new JsonObject();
        font.add("providers", providers);
        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(font) + "\n";

        Path fontFile = folder.resolve(FONT_FILE);
        Path textures = folder.resolve(TEXTURES);
        Path meta = folder.resolve("pack.mcmeta");
        boolean same = Files.exists(meta) && Files.exists(fontFile) && Files.readString(fontFile, StandardCharsets.UTF_8).equals(json)
                && pictures.keySet().stream().allMatch(name -> Files.exists(textures.resolve(name)));
        if (same) {
            return false;
        }
        Files.createDirectories(fontFile.getParent());
        Files.createDirectories(textures);
        try (Stream<Path> old = Files.list(textures)) {
            for (Path file : old.toList()) {
                if (!pictures.containsKey(file.getFileName().toString())) {
                    Files.delete(file);
                }
            }
        }
        for (Map.Entry<String, BufferedImage> picture : pictures.entrySet()) {
            ImageIO.write(picture.getValue(), "png", textures.resolve(picture.getKey()).toFile());
        }
        Files.writeString(fontFile, json, StandardCharsets.UTF_8);
        try (java.io.InputStream in = Badges.class.getResourceAsStream("/de/raindancer/core/pack/pack.mcmeta")) {
            if (in == null) {
                throw new IOException("Core's pack.mcmeta is missing");
            }
            Files.write(meta, in.readAllBytes());
        }
        return true;
    }

    /** Bedrock's glyph pages, by their path in a Bedrock pack: each badge cut into its cells. */
    public synchronized Map<String, BufferedImage> bedrockPages() {
        Map<String, BufferedImage> pages = new LinkedHashMap<>();
        for (Badge badge : byTag.values()) {
            BufferedImage art = BadgeArt.bedrock(badge.label(), badge.colours());
            for (int cell = 0; cell < badge.cells(); cell++) {
                char c = badge.characters().charAt(cell);
                String path = "font/glyph_" + Integer.toHexString(c >> 8).toUpperCase(Locale.ROOT) + ".png";
                BufferedImage page = pages.computeIfAbsent(path,
                        p -> new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB));
                int x0 = (c & 0xFF) % 16 * 16;
                int y0 = (c & 0xFF) / 16 * 16;
                for (int x = 0; x < 16 && cell * 16 + x < art.getWidth(); x++) {
                    for (int y = 0; y < 16; y++) {
                        page.setRGB(x0 + x, y0 + y, art.getRGB(cell * 16 + x, y));
                    }
                }
            }
        }
        return pages;
    }

    /** The line with every badge in it shown as its tag again: for somebody who does not have the pack. */
    public Component asText(Component line) {
        if (line == null) {
            return null;
        }
        Component drawn = line;
        if (line instanceof TextComponent text && FONT.equals(text.font()) && !text.content().isEmpty()) {
            Badge badge;
            synchronized (this) {
                badge = byFirst.get(text.content().charAt(0));
            }
            if (badge != null) {
                drawn = badge.tag().append(line.children().stream().map(this::asText).toList());
                return drawn;
            }
        }
        List<Component> children = new ArrayList<>(line.children().size());
        boolean changed = false;
        for (Component child : line.children()) {
            Component shown = asText(child);
            changed |= shown != child;
            children.add(shown);
        }
        return changed ? line.children(children) : line;
    }
}
