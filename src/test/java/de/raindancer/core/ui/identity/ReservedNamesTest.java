package de.raindancer.core.ui.identity;

import de.raindancer.core.ui.text.NameStyle;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReservedNamesTest {

    private static final UUID SOMEBODY = UUID.randomUUID();

    private static NameStyle style(String colours, boolean animated) {
        List<TextColor> stops = Arrays.stream(colours.split(" ")).map(TextColor::fromHexString).toList();
        return new NameStyle(stops, Set.of(), animated);
    }

    @Test
    @DisplayName("Raindancer118 wears Amethyst Flow on every server, bold and flowing, without setting anything")
    void ownStyle() {
        assertThat(ReservedNames.ownedBy(ReservedNames.RAINDANCER118)).contains(ReservedNames.AMETHYST_FLOW);
        assertThat(ReservedNames.AMETHYST_FLOW.isAnimated()).isTrue();
        assertThat(ReservedNames.AMETHYST_FLOW.has(TextDecoration.BOLD)).isTrue();
        assertThat(ReservedNames.ownedBy(SOMEBODY)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "the style itself|#5B1E96 #8932B8 #C77DFF #F1D6FF #C77DFF #8932B8|true",
            "every colour a shade off|#601F9B #8E37BD #CC82FF #F4DBFF #CC82FF #8E37BD|true",
            "the same, standing still|#5B1E96 #8932B8 #C77DFF #F1D6FF #C77DFF #8932B8|false",
            "purple, lilac, white flowing|#8932B8 #C77DFF #F1D6FF|true",
            "dark purple, purple, lilac, white|#5B1E96 #8932B8 #C77DFF #F1D6FF|true",
            "reversed|#8932B8 #C77DFF #F1D6FF #C77DFF #8932B8 #5B1E96|true",
    })
    @DisplayName("what looks like it is reserved for him, flowing or not, decorated or not")
    void lookalikes(String what, String colours, boolean animated) {
        NameStyle candidate = style(colours, animated);
        assertThat(ReservedNames.reservedAgainst(SOMEBODY, candidate)).as(what).isTrue();
        assertThat(ReservedNames.reservedAgainst(SOMEBODY, candidate.with(TextDecoration.BOLD, true))).isTrue();
        assertThat(ReservedNames.reservedAgainst(ReservedNames.RAINDANCER118, candidate)).as("his own").isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "a plain purple|#8932B8|false",
            "purple and pink|#8932B8 #FF5EDB|true",
            "Nebula|#8932B8 #FF5EDB #FFC2F0 #7B61FF|true",
            "a gold highlight instead|#5B1E96 #8932B8 #C77DFF #FFD86F #C77DFF #8932B8|true",
            "blue and violet|#4361EE #8932B8 #D16BFF #8932B8|true",
            "purple into black|#8932B8 #1D1D21|true",
            "much darker|#3C1361 #8932B8 #C77DFF #8932B8|true",
    })
    @DisplayName("purple alone, or purple with something else in it, stays everybody's")
    void free(String what, String colours, boolean animated) {
        assertThat(ReservedNames.reservedAgainst(SOMEBODY, style(colours, animated))).as(what).isFalse();
    }

    @Test
    @DisplayName("no style at all is nobody's")
    void none() {
        assertThat(ReservedNames.reservedAgainst(SOMEBODY, NameStyle.NONE)).isFalse();
        assertThat(ReservedNames.reservedAgainst(SOMEBODY, null)).isFalse();
    }

    @Test
    @DisplayName("his own presets: Amethyst Flow first, every one bold and flowing, named, none twice; nobody else has any")
    void presets() {
        List<ReservedNames.Preset> his = ReservedNames.presetsOf(ReservedNames.RAINDANCER118);
        assertThat(his).hasSizeGreaterThan(5);
        assertThat(his.getFirst().style()).isEqualTo(ReservedNames.AMETHYST_FLOW);
        assertThat(his).allSatisfy(preset -> {
            assertThat(preset.title()).isNotBlank();
            assertThat(preset.style().isAnimated()).isTrue();
            assertThat(preset.style().has(TextDecoration.BOLD)).isTrue();
            assertThat(preset.style().colours()).hasSizeBetween(2, NameStyle.MAX_STOPS);
        });
        assertThat(his.stream().map(ReservedNames.Preset::style).distinct()).hasSize(his.size());
        assertThat(ReservedNames.presetsOf(SOMEBODY)).isEmpty();
    }

}
