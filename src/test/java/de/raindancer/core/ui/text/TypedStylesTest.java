package de.raindancer.core.ui.text;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TypedStylesTest {

    @Test
    @DisplayName("colours in the order typed become a gradient; decorations and 'animated' anywhere among them")
    void reads() {
        TypedStyles.Read read = TypedStyles.read(List.of("gold", "bold", "#ff0000", "animated"), word -> null);
        assertThat(read.problem()).isEmpty();
        NameStyle style = read.style().orElseThrow();
        assertThat(style.colours()).containsExactly(NamedTextColor.GOLD, TextColor.fromHexString("#ff0000"));
        assertThat(style.decorations()).containsExactly(TextDecoration.BOLD);
        assertThat(style.isAnimated()).isTrue();
    }

    @Test
    @DisplayName("an extra colour source (a server's palette) is asked first")
    void palette() {
        TextColor dyeRed = TextColor.fromHexString("#b02e26");
        NameStyle style = TypedStyles.read(List.of("red"), word -> word.equals("red") ? dyeRed : null).style().orElseThrow();
        assertThat(style.colours()).containsExactly(dyeRed);
    }

    @Test
    @DisplayName("nothing typed, or a word that is no colour, is refused with a reason")
    void refused() {
        assertThat(TypedStyles.read(List.of(), word -> null).style()).isEmpty();
        TypedStyles.Read wrong = TypedStyles.read(List.of("gold", "sparkly"), word -> null);
        assertThat(wrong.style()).isEmpty();
        assertThat(wrong.problem()).contains("sparkly");
    }

    @Test
    @DisplayName("the spellings people use: underline, strike, magic")
    void spellings() {
        assertThat(TypedStyles.decorationOf("underline")).isEqualTo(TextDecoration.UNDERLINED);
        assertThat(TypedStyles.decorationOf("strike")).isEqualTo(TextDecoration.STRIKETHROUGH);
        assertThat(TypedStyles.decorationOf("magic")).isEqualTo(TextDecoration.OBFUSCATED);
        assertThat(TypedStyles.decorationOf("gold")).isNull();
    }
}
