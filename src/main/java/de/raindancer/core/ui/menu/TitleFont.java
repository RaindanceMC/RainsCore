package de.raindancer.core.ui.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.core.platform.util.FontWidth;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * How wide a title is in the painted menus' own font ({@code rainscore:title}, drawn in {@code art/menus}), read
 * from the glyphs the pack ships the way the game reads them: a glyph is as wide as its rightmost pixel, plus one.
 * What the font lacks falls back to the game's font, in the pack and here.
 */
public final class TitleFont {

    /** What a title may take: the window's width less its margins. */
    public static final int BUDGET = 160;
    private static final Map<Character, Integer> WIDTHS = load();

    private TitleFont() {
    }

    public static int width(String text) {
        int total = 0;
        for (char ch : text.toCharArray()) {
            total += width(ch);
        }
        return total;
    }

    public static int width(char ch) {
        if (ch == ' ') {
            return 4;
        }
        Integer own = WIDTHS.get(ch);
        return own != null ? own : FontWidth.of(ch, false);
    }

    private static Map<Character, Integer> load() {
        Map<Character, Integer> widths = new HashMap<>();
        try (InputStream json = TitleFont.class.getResourceAsStream(MenuPack.ROOT + "assets/rainscore/font/title.json");
             InputStream png = TitleFont.class.getResourceAsStream(
                     MenuPack.ROOT + "assets/rainscore/textures/font/title.png")) {
            if (json == null || png == null) {
                return widths;
            }
            BufferedImage sheet = ImageIO.read(png);
            JsonObject font = JsonParser.parseReader(new InputStreamReader(json, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement p : font.getAsJsonArray("providers")) {
                JsonObject provider = p.getAsJsonObject();
                if (!"bitmap".equals(provider.get("type").getAsString())) {
                    continue;
                }
                var rows = provider.getAsJsonArray("chars");
                int cellHeight = sheet.getHeight() / rows.size();
                for (int row = 0; row < rows.size(); row++) {
                    String chars = rows.get(row).getAsString();
                    int cellWidth = sheet.getWidth() / chars.length();
                    for (int column = 0; column < chars.length(); column++) {
                        char ch = chars.charAt(column);
                        if (ch != 0) {
                            widths.put(ch, rightmost(sheet, column * cellWidth, row * cellHeight, cellWidth,
                                    cellHeight) + 2);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            widths.clear();
        }
        return widths;
    }

    private static int rightmost(BufferedImage sheet, int x0, int y0, int w, int h) {
        for (int x = w - 1; x >= 0; x--) {
            for (int y = 0; y < h; y++) {
                if ((sheet.getRGB(x0 + x, y0 + y) >>> 24) != 0) {
                    return x;
                }
            }
        }
        return -1;
    }
}
