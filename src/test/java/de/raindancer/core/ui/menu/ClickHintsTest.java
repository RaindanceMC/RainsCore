package de.raindancer.core.ui.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.core.CoreConfig;
import de.raindancer.core.testkit.TestItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static de.raindancer.core.ui.menu.ClickHints.Audience.BEDROCK;
import static de.raindancer.core.ui.menu.ClickHints.Audience.JAVA;
import static de.raindancer.core.ui.menu.ClickHints.Audience.NONE;
import static org.assertj.core.api.Assertions.assertThat;

class ClickHintsTest {

    private static final char S = ClickHints.Glyph.SHIFT.character();
    private static final char L = ClickHints.Glyph.LEFT.character();
    private static final char R = ClickHints.Glyph.RIGHT.character();
    private static final char M = ClickHints.Glyph.MIDDLE.character();

    @AfterEach
    void reset() {
        ClickHints.uninstall();
    }

    private static String plain(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line);
    }

    private static Component mini(String text) {
        return MiniMessage.miniMessage().deserialize(text);
    }

    private static List<TextComponent> glyphsIn(Component line) {
        List<TextComponent> found = new ArrayList<>();
        collect(line, found);
        return found;
    }

    private static void collect(Component component, List<TextComponent> found) {
        if (component instanceof TextComponent text && !text.content().isEmpty()
                && ClickHints.Glyph.of(text.content().charAt(0)) != null) {
            found.add(text);
        }
        component.children().forEach(child -> collect(child, found));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Click to rename|L to rename",
            "Left click to buy one|L to buy one",
            "Left-Click to buy one|L to buy one",
            "Right click to sell all|R to sell all",
            "Right-click: remove|R: remove",
            "or right click to take one|or R to take one",
            "Shift click to move it up|SL to move it up",
            "Shift-click to move it up|SL to move it up",
            "Shift right click to move it down|SR to move it down",
            "Shift-Right-Click to move it down|SR to move it down",
            "Middle click to copy|M to copy",
            "Click to add one. Right click to take one.|L to add one. R to take one.",
    })
    @DisplayName("a click hint becomes the mouse it means, and the rest of the line stays")
    void hintsBecomeMice(String line, String expected) {
        Component shown = ClickHints.rewrite(Component.text(line), JAVA);
        assertThat(plain(shown)).isEqualTo(expectedText(expected));
    }

    /** The expected line with the placeholder letters swapped for glyphs, only where a letter is a word of its own. */
    private static String expectedText(String expected) {
        StringBuilder out = new StringBuilder();
        String[] words = expected.split("(?<= )|(?= )|(?=[:.])|(?<=[:.])");
        for (String word : words) {
            out.append(switch (word) {
                case "L" -> String.valueOf(L);
                case "R" -> String.valueOf(R);
                case "M" -> String.valueOf(M);
                case "SL" -> "" + S + L;
                case "SR" -> "" + S + R;
                default -> word;
            });
        }
        return out.toString();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "Clicked twice already",
            "a click opens it",
            "three clicks",
            "doubleClick",
            "Clicking does nothing here",
            "Click-through",
    })
    @DisplayName("words that only look like a hint are left alone")
    void prose(String line) {
        Component original = Component.text(line);
        assertThat(ClickHints.rewrite(original, JAVA)).isEqualTo(original);
    }

    @Test
    @DisplayName("the glyph is drawn from Core's hint font, white, upright and not bold, whatever the line around it")
    void javaGlyph() {
        Component shown = ClickHints.rewrite(mini("<yellow><bold><italic>Click<gray> to buy"), JAVA);
        List<TextComponent> glyphs = glyphsIn(shown);
        assertThat(glyphs).hasSize(1);
        TextComponent glyph = glyphs.getFirst();
        assertThat(glyph.content()).isEqualTo(String.valueOf(L));
        assertThat(glyph.font()).isEqualTo(ClickHints.FONT);
        assertThat(glyph.color()).isEqualTo(NamedTextColor.WHITE);
        assertThat(glyph.decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
        assertThat(glyph.decoration(TextDecoration.BOLD)).isEqualTo(TextDecoration.State.FALSE);
        assertThat(plain(shown)).isEqualTo(L + " to buy");
    }

    @Test
    @DisplayName("for Bedrock the same character without the font: Geyser drops fonts, Bedrock finds it on its glyph page")
    void bedrockGlyph() {
        Component shown = ClickHints.rewrite(Component.text("Shift click to move"), BEDROCK);
        List<TextComponent> glyphs = glyphsIn(shown);
        assertThat(glyphs).extracting(TextComponent::content).containsExactly(
                "" + S + ClickHints.BEDROCK_SHIFT_TAIL, String.valueOf(L));
        assertThat(glyphs).allSatisfy(glyph -> assertThat(glyph.font()).isNull());
    }

    @Test
    @DisplayName("nobody to draw for: the line is the very same")
    void none() {
        Component line = Component.text("Click to rename");
        assertThat(ClickHints.rewrite(line, NONE)).isSameAs(line);
    }

    @Test
    @DisplayName("who sees which: Java with the pack on, Bedrock only where the server has the Bedrock pack")
    void audience() {
        assertThat(ClickHints.audience(false, true, true, false)).isEqualTo(JAVA);
        assertThat(ClickHints.audience(false, false, true, true)).as("no pack, no font").isEqualTo(NONE);
        assertThat(ClickHints.audience(false, true, false, true)).as("switched off").isEqualTo(NONE);
        assertThat(ClickHints.audience(true, false, true, true)).isEqualTo(BEDROCK);
        assertThat(ClickHints.audience(true, true, true, false))
                .as("Geyser may answer the Java pack as loaded; a Bedrock client still has no Java font")
                .isEqualTo(NONE);
    }

    @Test
    @DisplayName("Floodgate's ids are Bedrock, a Java account's are not")
    void bedrockIds() {
        assertThat(ClickHints.isBedrock(new UUID(0, 42))).isTrue();
        assertThat(ClickHints.isBedrock(UUID.fromString("c9e2c821-7b5e-44e9-a5f7-7554a4da0120"))).isFalse();
    }

    @Test
    @DisplayName("a menu button's lore is shown with mice to whoever sees them; the button itself is not touched")
    void buttons() {
        UUID java = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ClickHints.install(id -> id.equals(java) ? JAVA : NONE);
        ItemStack button = TestItems.of(Material.NAME_TAG, meta -> meta.lore(List.of(
                mini("<gray>Your home"), mini("<yellow>Click<gray> to rename"))));

        ItemStack shown = Icons.forViewer(button, java);
        assertThat(shown).isNotSameAs(button);
        assertThat(shown.getItemMeta().lore()).extracting(ClickHintsTest::plain)
                .containsExactly("Your home", L + " to rename");
        assertThat(button.getItemMeta().lore()).extracting(ClickHintsTest::plain)
                .containsExactly("Your home", "Click to rename");
        assertThat(Icons.forViewer(button, other)).isSameAs(button);
    }

    @Test
    @DisplayName("a button without a hint is not copied")
    void nothingToDo() {
        UUID java = UUID.randomUUID();
        ClickHints.install(id -> JAVA);
        ItemStack button = TestItems.of(Material.STONE, meta -> meta.lore(List.of(Component.text("Just stone"))));
        assertThat(Icons.forViewer(button, java)).isSameAs(button);
    }

    @Test
    @DisplayName("Java: every glyph is in the hint font, its texture ships and is listed in the pack")
    void javaPack() throws IOException {
        Map<Character, String> files = new HashMap<>();
        try (InputStream in = MenuPack.class.getResourceAsStream(MenuPack.ROOT + "assets/rainscore/font/hints.json")) {
            assertThat(in).isNotNull();
            JsonObject font = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement provider : font.getAsJsonArray("providers")) {
                JsonObject p = provider.getAsJsonObject();
                files.put(p.getAsJsonArray("chars").get(0).getAsString().charAt(0), p.get("file").getAsString());
            }
        }
        assertThat(MenuPack.files()).contains("assets/rainscore/font/hints.json");
        for (ClickHints.Glyph glyph : ClickHints.Glyph.values()) {
            String file = files.get(glyph.character());
            assertThat(file).as(glyph.name()).isEqualTo("rainscore:font/hints/" + glyph.texture() + ".png");
            String path = "assets/rainscore/textures/font/hints/" + glyph.texture() + ".png";
            assertThat(MenuPack.files()).contains(path);
            try (InputStream png = MenuPack.class.getResourceAsStream(MenuPack.ROOT + path)) {
                assertThat(png).as(path).isNotNull();
                assertThat(ImageIO.read(png).getHeight()).isEqualTo(16);
            }
        }
    }

    @Test
    @DisplayName("Bedrock: every glyph has a drawing in its cell of the glyph page, starting at the cell's left edge")
    void bedrockPack() throws IOException {
        BufferedImage sheet;
        try (InputStream png = ClickHints.class.getResourceAsStream(
                ClickHints.BEDROCK_ROOT + "font/glyph_" + ClickHints.BEDROCK_PAGE + ".png")) {
            assertThat(png).isNotNull();
            sheet = ImageIO.read(png);
        }
        assertThat(sheet.getWidth()).isEqualTo(256);
        assertThat(sheet.getHeight()).isEqualTo(256);
        List<Character> drawn = new ArrayList<>();
        for (ClickHints.Glyph glyph : ClickHints.Glyph.values()) {
            drawn.add(glyph.character());
        }
        drawn.add(ClickHints.BEDROCK_SHIFT_TAIL);
        for (char character : drawn) {
            String glyph = Integer.toHexString(character);
            assertThat(Integer.toHexString(character >> 8).toUpperCase()).isEqualTo(ClickHints.BEDROCK_PAGE);
            int cell = character & 0xFF;
            int x0 = (cell % 16) * 16;
            int y0 = (cell / 16) * 16;
            boolean leftEdge = false;
            boolean any = false;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    if ((sheet.getRGB(x0 + x, y0 + y) >>> 24) != 0) {
                        any = true;
                        leftEdge |= x == 0;
                    }
                }
            }
            assertThat(any).as(glyph + " drawn").isTrue();
            // Bedrock measures a glyph from the cell's left edge to its last opaque column.
            assertThat(leftEdge).as(glyph + " starts at the left edge").isTrue();
        }
    }

    @Test
    @DisplayName("Core writes the Bedrock pack as an .mcpack for Geyser's packs folder: manifest and glyph page")
    void mcpack(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path written = ClickHints.writeBedrockPack(dir);
        assertThat(written.getFileName().toString()).isEqualTo("rainscore.mcpack");
        List<String> entries = new ArrayList<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(Files.newInputStream(written))) {
            for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                entries.add(entry.getName());
                if (entry.getName().equals("manifest.json")) {
                    JsonObject manifest = JsonParser.parseReader(new InputStreamReader(zip, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    assertThat(manifest.get("format_version").getAsInt()).isEqualTo(2);
                    assertThat(manifest.getAsJsonObject("header").get("uuid").getAsString()).isNotBlank();
                }
            }
        }
        assertThat(entries).contains("manifest.json", "font/glyph_" + ClickHints.BEDROCK_PAGE + ".png");
        assertThat(ClickHints.writeBedrockPack(dir)).as("written again over the old one").isEqualTo(written);
    }

    @Test
    @DisplayName("both switches are settings, on out of the box")
    void settings() {
        assertThat(CoreConfig.DEFAULTS.clickIcons()).isTrue();
        assertThat(CoreConfig.DEFAULTS.clickIconsBedrock()).isTrue();
    }
}
