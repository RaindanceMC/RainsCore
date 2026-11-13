package de.raindancer.core.platform.client;

import de.raindancer.client.protocol.Span;
import de.raindancer.client.protocol.Text;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class TextSpansTest {

    private static Text of(Component component) {
        return TextSpans.of(component, Locale.ENGLISH);
    }

    @Test
    void plainTextIsOneSpanInNoParticularColour() {
        assertThat(of(Component.text("Drachma"))).isEqualTo(new Text(java.util.List.of(new Span("Drachma", 0, 0, ""))));
        assertThat(of(Component.empty())).isEqualTo(Text.EMPTY);
    }

    @Test
    void colourAndStyleComeAlongAndChildrenInheritThem() {
        Component line = Component.text("Balance ", NamedTextColor.GRAY)
                .append(Component.text("12", TextColor.color(0xF6B83D), TextDecoration.BOLD))
                .append(Component.text(" ₯"));
        assertThat(of(line).spans()).containsExactly(
                new Span("Balance ", 0xFFAAAAAA, 0, ""),
                new Span("12", 0xFFF6B83D, Span.BOLD, ""),
                new Span(" ₯", 0xFFAAAAAA, 0, ""));
    }

    @Test
    void everyDecorationHasItsFlag() {
        Component all = Component.text("x").decorate(TextDecoration.BOLD, TextDecoration.ITALIC,
                TextDecoration.UNDERLINED, TextDecoration.STRIKETHROUGH, TextDecoration.OBFUSCATED);
        assertThat(of(all).spans().get(0).style())
                .isEqualTo(Span.BOLD | Span.ITALIC | Span.UNDERLINED | Span.STRIKETHROUGH | Span.OBFUSCATED);
    }

    @Test
    void aPackFontIsNamedSoItsIconsStayIcons() {
        Component hint = Component.empty().append(Component.text("\ue001").font(Key.key("rainscore", "hints")))
                .append(Component.text(" Left click"));
        assertThat(of(hint).spans()).containsExactly(new Span("\ue001", 0, 0, "rainscore:hints"),
                new Span(" Left click", 0, 0, ""));
    }

    @Test
    void neighboursInTheSameStyleAreOneSpan() {
        Component pieces = Component.text("In ", NamedTextColor.RED).append(Component.text("combat", NamedTextColor.RED));
        assertThat(of(pieces).spans()).containsExactly(new Span("In combat", 0xFFFF5555, 0, ""));
    }

    @Test
    void aGradientKeepsItsColoursLetterByLetter() {
        Text gradient = of(MiniMessage.miniMessage().deserialize("<gradient:#6EC1EA:#F6B83D>Hyades</gradient>"));
        assertThat(gradient.plain()).isEqualTo("Hyades");
        assertThat(gradient.spans()).hasSize(6);
        assertThat(gradient.spans().get(0).argb()).isEqualTo(0xFF6EC1EA);
    }

    @Test
    void nothingIsEverTooLongForTheWire() {
        Text long1 = of(Component.text("ä".repeat(900)));
        assertThat(long1.plain()).isEqualTo("ä".repeat(900));
        assertThat(long1.spans()).allSatisfy(span ->
                assertThat(span.text().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(256));

        Component rainbow = Component.empty();
        for (int letter = 0; letter < 300; letter++) {
            rainbow = rainbow.append(Component.text("x", TextColor.color(letter)));
        }
        Text many = of(rainbow);
        assertThat(many.spans()).hasSizeLessThanOrEqualTo(Text.MAX_SPANS);
        assertThat(many.plain()).as("the letters all arrive, only the last colours are given up").hasSize(300);
    }
}
