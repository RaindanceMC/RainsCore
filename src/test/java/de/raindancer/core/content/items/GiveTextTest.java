package de.raindancer.core.content.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GiveTextTest {

    private static String plain(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line);
    }

    private static final Component ROD = Component.text("[Void Rod]");

    @Test
    @DisplayName("the giver reads what went to whom: one name, a few names, or a count")
    void given() {
        assertThat(plain(GiveText.given(1, ROD, List.of("Ada")))).isEqualTo("✔ Gave 1 × [Void Rod] to Ada");
        assertThat(plain(GiveText.given(16, ROD, List.of("Ada", "Bo")))).isEqualTo("✔ Gave 16 × [Void Rod] to Ada and Bo");
        assertThat(plain(GiveText.given(2, ROD, List.of("Ada", "Bo", "Cy"))))
                .isEqualTo("✔ Gave 2 × [Void Rod] to Ada, Bo and Cy");
        assertThat(plain(GiveText.given(64, ROD, List.of("A", "B", "C", "D", "E"))))
                .isEqualTo("✔ Gave 64 × [Void Rod] to 5 players");
    }

    @Test
    @DisplayName("whoever gets it is told by whom, or simply that it came")
    void received() {
        assertThat(plain(GiveText.received(3, ROD, "Tom"))).isEqualTo("+ 3 × [Void Rod] from Tom");
        assertThat(plain(GiveText.received(1, ROD, null))).isEqualTo("+ 1 × [Void Rod]");
    }

    @Test
    @DisplayName("the usage line names the command and an example")
    void usage() {
        assertThat(plain(GiveText.usage())).contains("/give <player> <item> [amount]").contains("fishing:rod-void");
    }

    @Test
    @DisplayName("a mistyped item is answered with the likely one and a button")
    void didYouMean() {
        assertThat(plain(GiveText.didYouMean("fishing:rod-void", Component.text("[Give that]"))))
                .isEqualTo("? Did you mean fishing:rod-void? [Give that]");
    }
}
