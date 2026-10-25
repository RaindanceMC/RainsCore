package de.raindancer.core.ui.hub;

import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** On a network whose proxy takes /hub to send people to a hub server, the panel's hints must name what still opens it. */
class HubHintTest {

    private static String plain(String miniMessage) {
        return Text.plain(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    @Test
    @DisplayName("the hint names the panel's command as the server set it, with or without a slash, never as markup")
    void hint() {
        assertThat(plain(HubCommand.notFound("tpa", "hub"))).isEqualTo("No feature here is called tpa. Try /hub to see them all.");
        assertThat(plain(HubCommand.notFound("tpa", "/smp"))).isEqualTo("No feature here is called tpa. Try /smp to see them all.");
        assertThat(plain(HubCommand.notFound("<red>x", " "))).isEqualTo("No feature here is called <red>x. Try /hub to see them all.");
    }

    @Test
    @DisplayName("on a network /hub belongs to the proxy, so the panel is named smp unless the server chose otherwise")
    void name() {
        assertThat(HubCommand.name("", false)).isEqualTo("hub");
        assertThat(HubCommand.name("hub", false)).isEqualTo("hub");
        assertThat(HubCommand.name("", true)).isEqualTo("smp");
        assertThat(HubCommand.name(" /hub ", true)).isEqualTo("smp");
        assertThat(HubCommand.name("/menu", true)).isEqualTo("menu");
        assertThat(HubCommand.name(null, false)).isEqualTo("hub");
    }
}
