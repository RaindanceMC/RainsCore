package de.raindancer.core.ui.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every painting a menu can ask for is in the pack, the size of the window it is drawn behind. */
class MenuPackTest {

    private static InputStream open(String path) {
        InputStream in = MenuPack.class.getResourceAsStream(MenuPack.ROOT + path);
        assertThat(in).as(path).isNotNull();
        return in;
    }

    private static Map<Character, JsonObject> glyphs() throws IOException {
        Map<Character, JsonObject> found = new HashMap<>();
        try (InputStream in = open("assets/rainscore/font/menu.json")) {
            JsonObject font = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement provider : font.getAsJsonArray("providers")) {
                JsonObject p = provider.getAsJsonObject();
                if (p.get("type").getAsString().equals("bitmap")) {
                    for (JsonElement chars : p.getAsJsonArray("chars")) {
                        found.put(chars.getAsString().charAt(0), p);
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("Every height of both styles has a painting 176 wide and as tall as the window")
    void everyPainting() throws IOException {
        Map<Character, JsonObject> glyphs = glyphs();
        for (int rows = 1; rows <= 6; rows++) {
            for (Backdrop backdrop : new Backdrop[]{Backdrop.house(rows), Backdrop.workshop(rows)}) {
                char glyph = backdrop.glyphs().charAt(1);
                JsonObject provider = glyphs.get(glyph);
                assertThat(provider).as("glyph %04x", (int) glyph).isNotNull();
                assertThat(provider.get("height").getAsInt()).isEqualTo(114 + 18 * rows);
                String file = provider.get("file").getAsString().replace("rainscore:", "assets/rainscore/textures/");
                assertThat(MenuPack.files()).contains(file);
                try (InputStream in = open(file)) {
                    BufferedImage image = ImageIO.read(in);
                    assertThat(image.getWidth()).isEqualTo(176);
                    assertThat(image.getHeight()).isEqualTo(114 + 18 * rows);
                }
            }
        }
    }

    @Test
    @DisplayName("Every file in the index ships, and the filler's model is there")
    void indexShips() {
        assertThat(MenuPack.files()).contains("pack.mcmeta", "assets/rainscore/items/ui/blank.json",
                "assets/rainscore/models/item/ui/blank.json", "assets/rainscore/textures/item/ui/blank.png");
        for (String file : MenuPack.files()) {
            assertThat(MenuPack.class.getResource(MenuPack.ROOT + file)).as(file).isNotNull();
        }
    }
}
