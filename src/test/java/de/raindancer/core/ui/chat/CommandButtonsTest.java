package de.raindancer.core.ui.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.core.platform.util.FontWidth;
import de.raindancer.core.ui.menu.MenuPack;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommandButtonsTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** Every text in the line with the font and click it is drawn with, its parents' included. */
    private record Piece(String text, Key font, ClickEvent click) {
    }

    private static List<Piece> pieces(Component line) {
        List<Piece> out = new ArrayList<>();
        walk(line, null, null, out);
        return out;
    }

    private static void walk(Component c, Key font, ClickEvent click, List<Piece> out) {
        Key f = c.font() != null ? c.font() : font;
        ClickEvent k = c.clickEvent() != null ? c.clickEvent() : click;
        if (c instanceof TextComponent t && !t.content().isEmpty()) {
            out.add(new Piece(t.content(), f, k));
        }
        c.children().forEach(child -> walk(child, f, k, out));
    }

    @Test
    @DisplayName("[/bugreport] becomes a button that runs it; the words around it stay words")
    void textButton() {
        Component shown = CommandButtons.render(Component.text("found one? use [/bugreport] please"), false);
        assertThat(plain(shown)).isEqualTo("found one? use [/bugreport] please");
        List<Piece> pieces = pieces(shown);
        assertThat(pieces).filteredOn(p -> p.text().contains("/bugreport"))
                .allSatisfy(p -> assertThat(p.click()).isEqualTo(ClickEvent.runCommand("/bugreport")));
        assertThat(pieces).filteredOn(p -> p.text().contains("please") || p.text().contains("found"))
                .allSatisfy(p -> assertThat(p.click()).isNull());
    }

    @Test
    @DisplayName("with arguments, and up to three in one line")
    void arguments() {
        Component shown = CommandButtons.render(Component.text("[/warp spawn] or [/home base] or [/spawn] or [/help]"),
                false);
        List<ClickEvent> clicks = pieces(shown).stream().map(Piece::click).filter(java.util.Objects::nonNull)
                .distinct().toList();
        assertThat(clicks).containsExactly(ClickEvent.runCommand("/warp spawn"), ClickEvent.runCommand("/home base"),
                ClickEvent.runCommand("/spawn"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[OWNER] hi", "[/] nothing", "[ /spawn]", "a [b] c", "no brackets /spawn"})
    @DisplayName("anything else in brackets is left alone")
    void untouched(String text) {
        Component line = Component.text(text);
        assertThat(CommandButtons.render(line, false)).isSameAs(line);
    }

    @Test
    @DisplayName("drawn: a button as wide as its text and some, the text on it in the game's font, the pen where it ends")
    void drawn() {
        Component shown = CommandButtons.render(Component.text("[/bugreport]"), true);
        List<Piece> pieces = pieces(shown);
        Piece text = pieces.stream().filter(p -> p.text().equals("/bugreport")).findFirst().orElseThrow();
        assertThat(text.font()).isEqualTo(Key.key("minecraft", "default"));
        assertThat(pieces).allSatisfy(p -> assertThat(p.click()).isEqualTo(ClickEvent.runCommand("/bugreport")));

        int pen = 0;
        int textStart = -1;
        for (Piece piece : pieces) {
            if (piece.font() != null && piece.font().equals(CommandButtons.FONT)) {
                for (char c : piece.text().toCharArray()) {
                    pen += CommandButtons.advance(c);
                }
            } else {
                textStart = pen;
                pen += FontWidth.of(piece.text());
            }
        }
        int textWidth = FontWidth.of("/bugreport");
        int width = CommandButtons.width(textWidth);
        assertThat(textStart).isEqualTo(CommandButtons.CAP + CommandButtons.PAD);
        assertThat(pen).isEqualTo(width);
        assertThat(width).isEqualTo(textWidth - 1 + 2 * CommandButtons.PAD + 2 * CommandButtons.CAP);
    }

    @Test
    @DisplayName("any width of background is made of the tiles there are")
    void tiles() {
        for (int width = 1; width <= 400; width++) {
            int sum = 0;
            for (char c : CommandButtons.middle(width).toCharArray()) {
                sum += CommandButtons.advance(c);
            }
            assertThat(sum).as("width " + width).isEqualTo(width);
        }
    }

    @Test
    @DisplayName("every piece of the button is in the pack's font, its picture there too")
    void pack() throws IOException {
        Map<Character, JsonObject> bitmaps = new HashMap<>();
        Map<String, Integer> spaces = new HashMap<>();
        try (InputStream in = MenuPack.class.getResourceAsStream("/de/raindancer/core/pack/assets/rainscore/font/buttons.json")) {
            assertThat(in).isNotNull();
            JsonObject font = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement p : font.getAsJsonArray("providers")) {
                JsonObject provider = p.getAsJsonObject();
                if (provider.get("type").getAsString().equals("bitmap")) {
                    bitmaps.put(provider.getAsJsonArray("chars").get(0).getAsString().charAt(0), provider);
                } else {
                    provider.getAsJsonObject("advances").entrySet()
                            .forEach(e -> spaces.put(e.getKey(), e.getValue().getAsInt()));
                }
            }
        }
        for (char c : CommandButtons.TILES) {
            JsonObject bitmap = bitmaps.get(c);
            assertThat(bitmap).as("tile " + (int) c).isNotNull();
            String path = "assets/rainscore/textures/" + bitmap.get("file").getAsString().substring("rainscore:".length());
            assertThat(MenuPack.files()).contains(path);
        }
        for (int power = 0; power < 8; power++) {
            assertThat(spaces.get(String.valueOf((char) (CommandButtons.BACK + power)))).isEqualTo(-(1 << power));
            assertThat(spaces.get(String.valueOf((char) (CommandButtons.FORWARD + power)))).isEqualTo(1 << power);
        }
    }

    private static final net.kyori.adventure.text.minimessage.MiniMessage MM =
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();

    @Test
    @DisplayName("a command written on its own in a plugin's line becomes a button: /role in the middle of a sentence")
    void bareCommandInTheMiddle() {
        Component line = MM.deserialize("<gray>You have no role yet. <white>/role</white> — a cook, a builder…");

        Component shown = CommandButtons.bare(line, false);

        assertThat(pieces(shown)).filteredOn(p -> p.click() != null).extracting(Piece::click)
                .containsOnly(ClickEvent.runCommand("/role"));
        assertThat(plain(shown)).isEqualTo("You have no role yet. [/role] — a cook, a builder…");
    }

    @Test
    @DisplayName("at the end of a line, with its words: /fishing skills")
    void bareCommandWithWords() {
        Component shown = CommandButtons.bare(MM.deserialize("<gray>Spend them — <white>/fishing skills</white>."), false);

        assertThat(pieces(shown)).filteredOn(p -> p.click() != null).extracting(Piece::click)
                .containsOnly(ClickEvent.runCommand("/fishing skills"));
        assertThat(plain(shown)).isEqualTo("Spend them — [/fishing skills].");
    }

    @Test
    @DisplayName("drawn for a player wearing the pack, in Core's button font")
    void bareCommandDrawn() {
        Component shown = CommandButtons.bare(MM.deserialize("<gray>You have 3 points. <white>/skills"), true);

        assertThat(pieces(shown)).anySatisfy(p -> assertThat(p.font()).isEqualTo(CommandButtons.FONT));
        assertThat(pieces(shown)).filteredOn(p -> "/skills".equals(p.text())).extracting(Piece::click)
                .containsOnly(ClickEvent.runCommand("/skills"));
    }

    @Test
    @DisplayName("a slash inside words, a placeholder or something already clickable stays as it is")
    void notCommands() {
        for (String text : new String[]{
                "<gray>Use and/or <white>this</white>.",
                "<gray>Type <white>/tpa [player]</white> to ask.",
                "<gray>Read the <white>/rules and all the long explanations of what they mean</white>",
                "<click:run_command:/help><white>/help</white></click>"}) {
            Component line = MM.deserialize(text);
            assertThat(CommandButtons.bare(line, false)).as(text).isSameAs(line);
        }
    }
}
