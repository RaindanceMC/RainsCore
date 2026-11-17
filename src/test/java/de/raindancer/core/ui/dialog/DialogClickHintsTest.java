package de.raindancer.core.ui.dialog;

import de.raindancer.core.ui.menu.ClickHints;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A dialog button's tooltip gets the same mouse icons as a chest page's lore. */
class DialogClickHintsTest {

    private final UUID wearing = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        ClickHints.uninstall();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void theTooltipsClickBecomesTheMouse() {
        ClickHints.install(id -> id.equals(wearing) ? ClickHints.Audience.JAVA : ClickHints.Audience.NONE);
        DialogPage.Button button = DialogPage.titled("<white>Test", "Here")
                .button("North", "<white>North\n\n<green>Click to go", answers -> { })
                .pressable().getFirst();

        assertThat(plain(button.tooltipFor(wearing))).doesNotContain("Click to go").contains("go");
        assertThat(plain(button.tooltipFor(UUID.randomUUID()))).contains("Click to go");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a button whose tooltip says nothing about clicking still shows the mouse, as a chest page does")
    void everyButtonGetsTheMouse() {
        ClickHints.install(id -> id.equals(wearing) ? ClickHints.Audience.JAVA : ClickHints.Audience.NONE);
        DialogPage page = DialogPage.titled("<white>Test")
                .button("Profile", "<gray>How the server sees you.", answers -> { })
                .button("Bare", null, answers -> { })
                .button(false, "Locked", "<gray>Not for you.", "Your rank cannot.", answers -> { });
        char mouse = ClickHints.Glyph.LEFT.character();

        assertThat(plain(page.button("Profile").tooltipFor(wearing))).contains("How the server sees you.")
                .contains(String.valueOf(mouse));
        assertThat(plain(page.button("Bare").tooltipFor(wearing))).contains(String.valueOf(mouse));
        assertThat(plain(page.button("Locked").tooltipFor(wearing))).as("a greyed button cannot be clicked")
                .doesNotContain(String.valueOf(mouse));
        assertThat(plain(page.button("Profile").tooltipFor(UUID.randomUUID()))).as("nobody to draw it for")
                .doesNotContain(String.valueOf(mouse));
        assertThat(plain(DialogPage.titled("<white>Test").button("Go", "<gray>Off.\n\nClick to go", answers -> { })
                .button("Go").tooltipFor(wearing)).chars().filter(c -> c == mouse).count())
                .as("one that already says it gets no second").isEqualTo(1);
    }
}
