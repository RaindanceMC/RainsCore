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
}
