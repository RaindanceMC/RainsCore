package de.raindancer.core.ui.dialog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FacesTest {

    /** Every glyph of a face, in reading order, with its colour. */
    private static List<TextComponent> glyphs(Component face) {
        List<TextComponent> found = new ArrayList<>();
        collect(face, found);
        return found;
    }

    private static void collect(Component component, List<TextComponent> into) {
        if (component instanceof TextComponent text && text.content().startsWith(Faces.PIXEL)) {
            into.add(text);
        }
        component.children().forEach(child -> collect(child, into));
    }

    private static BufferedImage skin() {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 8; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                skin.setRGB(x, y, 0xFF000000 | (x * 16 << 16) | (y * 16 << 8) | 0x20);
            }
        }
        return skin;
    }

    @Test
    @DisplayName("a face is eight rows of eight pixels in the skin's own colours, in the face font")
    void eightByEight() {
        Component face = Faces.face(skin());
        List<TextComponent> pixels = glyphs(face);
        assertThat(pixels).hasSize(64);
        assertThat(pixels.getFirst().color()).isEqualTo(TextColor.color(8 * 16, 8 * 16, 0x20));
        assertThat(pixels.get(63).color()).isEqualTo(TextColor.color(15 * 16, 15 * 16, 0x20));
        assertThat(face.style().font()).isEqualTo(Faces.FONT);
        assertThat(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(face)
                .split("\n")).hasSize(8);
    }

    @Test
    @DisplayName("the hat layer covers the face where it is drawn, and lets it through where it is not")
    void hatOnTop() {
        BufferedImage skin = skin();
        skin.setRGB(40, 8, 0xFFFF0000);       // the hat's top-left pixel, opaque red
        skin.setRGB(41, 8, 0x00FFFFFF);       // the next, see-through
        List<TextComponent> pixels = glyphs(Faces.face(skin));
        assertThat(pixels.get(0).color()).isEqualTo(TextColor.color(0xFF0000));
        assertThat(pixels.get(1).color()).isEqualTo(TextColor.color(9 * 16, 8 * 16, 0x20));
    }

    @Test
    @DisplayName("an old 64×32 skin works, and a picture that is no skin gives no face")
    void oddSkins() {
        BufferedImage old = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        assertThat(glyphs(Faces.face(old))).hasSize(64);
        assertThat(Faces.face(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB))).isNull();
    }

    @Test
    @DisplayName("nobody's face is known before it was fetched; a Bedrock id never has one")
    void unknownUntilFetched() {
        assertThat(Faces.cached(UUID.randomUUID())).isEmpty();
        assertThat(Faces.isBedrock(new UUID(0, 42))).isTrue();
        assertThat(Faces.isBedrock(UUID.randomUUID())).isFalse();
    }
}
