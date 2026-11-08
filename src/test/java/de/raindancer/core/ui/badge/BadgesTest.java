package de.raindancer.core.ui.badge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BadgesTest {

    private static final String OWNER = "<bold><#8E0E00>[</#8E0E00><#B42410>O</#B42410><#D93B1F>W</#D93B1F>"
            + "<#FF512F>N</#FF512F><#FA6928>E</#FA6928><#F58020>R</#F58020><#F09819>]";
    private static final String CREATOR = "<bold><gradient:#ff4fa3:#a35cff>[▶ CREATOR]</gradient></bold>";

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static TextComponent glyphRun(Component badge) {
        List<TextComponent> runs = new ArrayList<>();
        collect(badge, runs);
        return runs.getFirst();
    }

    private static void collect(Component c, List<TextComponent> out) {
        if (c instanceof TextComponent t && !t.content().isEmpty()) {
            out.add(t);
        }
        c.children().forEach(child -> collect(child, out));
    }

    @Test
    @DisplayName("a tag's letters become the badge's label: brackets and symbols the badge font has not are left out")
    void labels() {
        assertThat(Badges.label(OWNER)).isEqualTo("OWNER");
        assertThat(Badges.label(CREATOR)).isEqualTo("CREATOR");
        assertThat(Badges.label("<gray>[tester]</gray>")).isEqualTo("TESTER");
        assertThat(Badges.label("<red>[VIP+]")).isEqualTo("VIP+");
        assertThat(Badges.label("<red>[]")).isEmpty();
    }

    @Test
    @DisplayName("its colours become the badge's gradient, in order; one colour is a flat badge, none is grey")
    void colours() {
        List<TextColor> owner = Badges.colours(OWNER);
        assertThat(owner.getFirst()).isEqualTo(TextColor.fromHexString("#8E0E00"));
        assertThat(owner.getLast()).isEqualTo(TextColor.fromHexString("#F09819"));
        assertThat(owner).hasSizeLessThanOrEqualTo(Badges.MAX_STOPS);

        List<TextColor> creator = Badges.colours(CREATOR);
        assertThat(creator.getFirst()).isEqualTo(TextColor.fromHexString("#ff4fa3"));
        assertThat(creator.getLast().asHexString()).isEqualToIgnoringCase("#a35cff");

        assertThat(Badges.colours("<dark_red><bold>[ADMIN]")).containsExactly(NamedTextColor.DARK_RED);
        assertThat(Badges.colours("[PLAIN]")).containsExactly(NamedTextColor.GRAY);
    }

    @Test
    @DisplayName("a badge is one glyph in the badge font for Java, followed by its Bedrock cells")
    void component() {
        Badges badges = new Badges();
        Component owner = badges.register(OWNER).orElseThrow();
        TextComponent run = glyphRun(owner);
        assertThat(run.font()).isEqualTo(Badges.FONT);
        assertThat(run.color()).isEqualTo(NamedTextColor.WHITE);
        Badge badge = badges.all().getFirst();
        assertThat(run.content()).isEqualTo(badge.characters());
        assertThat(badge.characters().length()).isEqualTo(badge.cells());
        assertThat(badge.cells()).isEqualTo((BadgeArt.bedrock(badge.label(), badge.colours()).getWidth() + 15) / 16);
        assertThat(badges.register(OWNER)).as("the same tag, the same badge").contains(owner);
    }

    @Test
    @DisplayName("badges never share a character, and a tag without letters is no badge")
    void distinct() {
        Badges badges = new Badges();
        badges.register(OWNER);
        badges.register(CREATOR);
        badges.register("<aqua>[DEV]");
        List<Character> seen = new ArrayList<>();
        for (Badge badge : badges.all()) {
            for (char c : badge.characters().toCharArray()) {
                assertThat(seen).doesNotContain(c);
                seen.add(c);
            }
        }
        assertThat(badges.register("<red>[★]")).isEmpty();
    }

    @Test
    @DisplayName("the art: Java's fills a 20 pixel line with white letters on the gradient, Bedrock's a 16 pixel cell")
    void art() {
        BufferedImage java = BadgeArt.java("OWNER", Badges.colours(OWNER));
        assertThat(java.getHeight()).isEqualTo(BadgeArt.JAVA_HEIGHT);
        assertThat(java.getWidth()).isEqualTo(BadgeArt.width("OWNER"));
        assertThat(java.getRGB(BadgeArt.PAD, BadgeArt.JAVA_HEIGHT / 2) | 0).as("the O's left stroke").isEqualTo(0xFFFFFFFF);
        assertThat(java.getRGB(0, 0) >>> 24).as("a rounded corner").isZero();
        assertThat(java.getRGB(java.getWidth() / 2, 0) >>> 24).as("the top edge").isEqualTo(255);

        BufferedImage bedrock = BadgeArt.bedrock("OWNER", Badges.colours(OWNER));
        assertThat(bedrock.getHeight()).isEqualTo(16);
    }

    @Test
    @DisplayName("Java's font: the glyph a whole badge tall on the line, the Bedrock cells after it taking no room")
    void javaFont(@TempDir Path folder) throws IOException {
        Badges badges = new Badges();
        badges.register(OWNER);
        badges.register(CREATOR);
        assertThat(badges.writeJava(folder)).isTrue();
        assertThat(badges.writeJava(folder)).as("nothing new, nothing written").isFalse();
        assertThat(folder.resolve("pack.mcmeta")).as("a pack of its own, or the pack builder leaves it out").exists();
        assertThat(Files.readString(folder.resolve("pack.mcmeta"))).contains("min_format");

        JsonObject font = JsonParser.parseString(Files.readString(
                folder.resolve("assets/rainscore/font/badges.json"))).getAsJsonObject();
        Map<String, JsonObject> bitmaps = new java.util.HashMap<>();
        JsonObject spaces = null;
        for (JsonElement p : font.getAsJsonArray("providers")) {
            JsonObject provider = p.getAsJsonObject();
            if (provider.get("type").getAsString().equals("bitmap")) {
                bitmaps.put(provider.getAsJsonArray("chars").get(0).getAsString(), provider);
            } else {
                spaces = provider.getAsJsonObject("advances");
            }
        }
        for (Badge badge : badges.all()) {
            String first = badge.characters().substring(0, 1);
            JsonObject bitmap = bitmaps.get(first);
            assertThat(bitmap.get("height").getAsInt()).isEqualTo(9);
            assertThat(bitmap.get("ascent").getAsInt()).isEqualTo(8);
            Path png = folder.resolve("assets/rainscore/textures/" + bitmap.get("file").getAsString()
                    .substring("rainscore:".length()));
            assertThat(ImageIO.read(png.toFile()).getHeight()).isEqualTo(BadgeArt.JAVA_HEIGHT);
            for (char tail : badge.characters().substring(1).toCharArray()) {
                assertThat(spaces.get(String.valueOf(tail)).getAsInt()).isZero();
            }
        }
    }

    @Test
    @DisplayName("Bedrock's glyph pages: each badge's cells drawn, side by side, starting at a cell's left edge")
    void bedrockPages() {
        Badges badges = new Badges();
        badges.register(OWNER);
        badges.register(CREATOR);
        Map<String, BufferedImage> pages = badges.bedrockPages();
        assertThat(pages).isNotEmpty();
        for (Badge badge : badges.all()) {
            for (char c : badge.characters().toCharArray()) {
                BufferedImage page = pages.get("font/glyph_" + Integer.toHexString(c >> 8).toUpperCase() + ".png");
                assertThat(page.getWidth()).isEqualTo(256);
                int x0 = (c & 0xFF) % 16 * 16;
                int y0 = (c & 0xFF) / 16 * 16;
                assertThat(page.getRGB(x0, y0 + 8) >>> 24).as(badge.label() + " cell " + (int) c).isEqualTo(255);
            }
        }
    }

    @Test
    @DisplayName("for somebody without the pack a badge is its tag again; anything else in the line stays")
    void asText() {
        Badges badges = new Badges();
        Component owner = badges.register(OWNER).orElseThrow();
        Component line = Component.text("» ").append(owner).append(Component.text(" Raindancer118: hi"));
        Component shown = badges.asText(line);
        assertThat(plain(shown)).isEqualTo("» [OWNER] Raindancer118: hi");
        Component plainLine = Component.text("nothing to do");
        assertThat(badges.asText(plainLine)).isSameAs(plainLine);
    }

    @Test
    @DisplayName("every letter, digit and sign a label may hold has a drawing")
    void font() {
        for (char c : BadgeArt.SUPPORTED.toCharArray()) {
            assertThat(BadgeArt.glyph(c)).as(String.valueOf(c)).isNotNull();
        }
        assertThat(Optional.ofNullable(BadgeArt.glyph('★'))).isEmpty();
    }
}
