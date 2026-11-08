package de.raindancer.core.ui.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.core.CoreConfig;
import de.raindancer.core.testkit.TestItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
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

    /** {L}, {R}, {M}, {S} stand for the glyphs, ¦ for the bar. */
    private static String glyphs(String expected) {
        return expected.replace("{L}", String.valueOf(L)).replace("{R}", String.valueOf(R))
                .replace("{M}", String.valueOf(M)).replace("{S}", String.valueOf(S)).replace('¦', '|');
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Click to rename|{L} ¦ rename",
            "Left click to buy one|{L} ¦ buy one",
            "Left-Click to buy one|{L} ¦ buy one",
            "Right click to sell all|{R} ¦ sell all",
            "Right-click: remove|{R} ¦ remove",
            "or right click to take one|or {R} ¦ take one",
            "Shift click to move it up|{S}{L} ¦ move it up",
            "Shift-click to move it up|{S}{L} ¦ move it up",
            "Shift right click to move it down|{S}{R} ¦ move it down",
            "Shift-Right-Click to move it down|{S}{R} ¦ move it down",
            "Middle click to copy|{M} ¦ copy",
            "Click to add one. Right click to take one.|{L} ¦ add one. {R} ¦ take one.",
            "Click a slot first|{L} a slot first",
            "Click|{L}",
    })
    @DisplayName("a click hint becomes the mouse it means: [mouse] | what it does")
    void hintsBecomeMice(String line, String expected) {
        Component shown = ClickHints.rewrite(Component.text(line), JAVA);
        assertThat(plain(shown)).isEqualTo(glyphs(expected));
    }

    @Test
    @DisplayName("the hint and its 'to' in different colours still become one icon and a bar; the rest keeps its colour")
    void acrossColours() {
        Component shown = ClickHints.rewrite(mini("<yellow>Right click<gray> to open <white>the chest"), JAVA);
        assertThat(plain(shown)).isEqualTo(R + " | open the chest");
        assertThat(colourOf(shown, "open")).isEqualTo(NamedTextColor.GRAY);
        assertThat(colourOf(shown, "the chest")).isEqualTo(NamedTextColor.WHITE);
        assertThat(colourOf(shown, "|")).isEqualTo(NamedTextColor.DARK_GRAY);
    }

    /** The effective colour of the first text component whose content starts with {@code text}. */
    private static TextColor colourOf(Component line, String text) {
        return find(line, text, null);
    }

    private static TextColor find(Component c, String text, TextColor inherited) {
        TextColor colour = c.color() != null ? c.color() : inherited;
        if (c instanceof TextComponent t && t.content().startsWith(text)) {
            return colour;
        }
        for (Component child : c.children()) {
            TextColor found = find(child, text, colour);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    @DisplayName("only the icon is in the hint font: everything after it keeps the game's font (it showed as boxes)")
    void fontStaysOnTheIcon() {
        Component shown = ClickHints.rewrite(mini("<!italic><yellow>Click<gray> to show it to yourself"), JAVA);
        List<TextComponent> pieces = new ArrayList<>();
        texts(shown, null, pieces);
        for (TextComponent piece : pieces) {
            boolean glyph = ClickHints.Glyph.of(piece.content().charAt(0)) != null;
            assertThat(piece.font()).as("'" + piece.content() + "'").isEqualTo(glyph ? ClickHints.FONT : null);
        }
        assertThat(pieces).extracting(TextComponent::content).contains("show it to yourself");
    }

    /** Every non-empty text, with the font it is drawn in once its parents' styles are applied. */
    private static void texts(Component c, net.kyori.adventure.key.Key inherited, List<TextComponent> out) {
        net.kyori.adventure.key.Key font = c.font() != null ? c.font() : inherited;
        if (c instanceof TextComponent t && !t.content().isEmpty()) {
            out.add(t.font(font));
        }
        c.children().forEach(child -> texts(child, font, out));
    }

    @Test
    @DisplayName("a chat button keeps its click and its hover on the icon, and the hover's own hint is drawn too")
    void chatButton() {
        ClickEvent accept = ClickEvent.runCommand("/tpaccept");
        Component button = Component.text("[Click to accept]", NamedTextColor.GREEN).clickEvent(accept)
                .hoverEvent(HoverEvent.showText(Component.text("Click to accept Rain")));
        Component shown = ClickHints.rewrite(Component.text("Rain wants to visit. ").append(button), JAVA);

        assertThat(plain(shown)).isEqualTo("Rain wants to visit. [" + L + " | accept]");
        TextComponent glyph = glyphsIn(shown).getFirst();
        assertThat(glyph.clickEvent()).isEqualTo(accept);
        Component hover = (Component) glyph.hoverEvent().value();
        assertThat(plain(hover)).isEqualTo(L + " | accept Rain");
    }

    @Test
    @DisplayName("something that is not plain text, like a translated name, is kept and never matched into")
    void opaque() {
        Component line = Component.text("Click to give ").append(Component.translatable("item.minecraft.diamond"));
        Component shown = ClickHints.rewrite(line, JAVA);
        assertThat(plain(shown)).isEqualTo(L + " | give item.minecraft.diamond");
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
        assertThat(plain(shown)).isEqualTo(L + " | buy");
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
                .containsExactly("Your home", L + " | rename");
        assertThat(button.getItemMeta().lore()).extracting(ClickHintsTest::plain)
                .containsExactly("Your home", "Click to rename");
        assertThat(Icons.forViewer(button, other)).isSameAs(button);
    }

    @Test
    @DisplayName("an item in somebody's inventory: a copy with its lore drawn, the original untouched; none needed, none made")
    void items() {
        ItemStack wand = TestItems.of(Material.BLAZE_ROD, meta -> meta.lore(List.of(mini("<gray>Right click a chest to sell it"))));
        ItemStack shown = ClickHints.rewrite(wand, JAVA);
        assertThat(shown).isNotSameAs(wand);
        assertThat(shown.getItemMeta().lore()).extracting(ClickHintsTest::plain).containsExactly(R + " a chest to sell it");
        assertThat(wand.getItemMeta().lore()).extracting(ClickHintsTest::plain).containsExactly("Right click a chest to sell it");

        ItemStack stone = TestItems.of(Material.STONE);
        assertThat(ClickHints.rewrite(stone, JAVA)).isSameAs(stone);
        assertThat(ClickHints.rewrite(wand, NONE)).isSameAs(wand);
        assertThat(ClickHints.rewrite((ItemStack) null, JAVA)).isNull();
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
