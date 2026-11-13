package de.raindancer.core.ui.actionbar;

import de.raindancer.core.platform.util.FontWidth;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Two lines above the hotbar: the action bar draws one line, so the second is a font set lower, laid under the first. */
class TwoLinesTest {

    @Test
    @DisplayName("spacing adds up to exactly the pixels asked for, either way, with a handful of glyphs")
    void spacing() {
        for (int px : new int[]{0, 1, 7, -1, -13, 150, -300, 511, -511}) {
            String spaces = TwoLines.spaces(px);
            assertThat(spaces.chars().map(TwoLines::advance).sum()).as(px + " px").isEqualTo(px);
            assertThat(spaces.length()).as(px + " px").isLessThanOrEqualTo(10);
        }
    }

    @Test
    @DisplayName("both lines come out centred: the whole is as wide as the upper line, the lower centred under it")
    void centred() {
        String upper = "Away 4 min";
        String lower = "Lost? /rtp drops you somewhere new. Somewhere very new.";
        Component both = TwoLines.of(Component.text(upper), Component.text(lower));
        int a = FontWidth.of(upper);
        int b = FontWidth.of(lower);
        int total = 0;
        int lowerStart = 0;
        for (Component part : flatten(both)) {
            String text = ((TextComponent) part).content();
            if (TwoLines.FONT.equals(part.font()) && text.equals(lower)) {
                lowerStart = total;
            }
            total += text.chars().map(ch -> TwoLines.isSpacing((char) ch) ? TwoLines.advance(ch)
                    : FontWidth.of(String.valueOf((char) ch))).sum();
        }
        assertThat(total).as("the action bar centres the whole on the upper line").isEqualTo(a);
        assertThat(lowerStart + b / 2.0 - total / 2.0).as("the lower line's middle sits on the whole's middle")
                .isBetween(-1.0, 1.0);
    }

    @Test
    @DisplayName("the font is in Core's pack and listed, shifted down under the line, with the spacing glyphs")
    void font() throws Exception {
        Path pack = Path.of("src/main/resources/de/raindancer/core/pack");
        assertThat(Files.readString(pack.resolve("files.txt"))).contains("assets/rainscore/font/below.json");
        String json = Files.readString(pack.resolve("assets/rainscore/font/below.json"));
        assertThat(json).contains("minecraft:font/ascii.png").contains("\"type\": \"space\"");
        // the first provider with a glyph wins: the space must come before ascii.png, whose space cell is empty
        assertThat(json.indexOf("\"type\": \"space\"")).as("spaces first, as the game's own font has them")
                .isLessThan(json.indexOf("minecraft:font/ascii.png"));
        assertThat(TwoLines.FONT).isEqualTo(Key.key("rainscore", "below"));
    }

    private static java.util.List<Component> flatten(Component c) {
        java.util.List<Component> out = new java.util.ArrayList<>();
        Component self = c.children(java.util.List.of());
        if (self instanceof TextComponent t && !t.content().isEmpty()) {
            out.add(t.font() == null && c.font() != null ? t.font(c.font()) : t);
        }
        for (Component child : c.children()) {
            for (Component inner : flatten(child)) {
                out.add(inner.font() == null && c.font() != null ? inner.font(c.font()) : inner);
            }
        }
        return out;
    }
}
