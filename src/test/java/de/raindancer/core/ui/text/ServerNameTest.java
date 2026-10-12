package de.raindancer.core.ui.text;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServerNameTest {

    @Test
    @DisplayName("the setting wins over link.yml and the MOTD, trimmed")
    void settingWins() {
        assertThat(ServerName.resolve("  Lilly's SMP ", "SMP", Component.text("A Minecraft Server")))
                .isEqualTo("Lilly's SMP");
    }

    @Test
    @DisplayName("without the setting, link.yml's server-name, then the MOTD's first line")
    void fallbacks() {
        assertThat(ServerName.resolve("", "SMP", Component.text("motd"))).isEqualTo("SMP");
        assertThat(ServerName.resolve(" ", null, Component.text("  Lilly's SMP \nsecond line")))
                .isEqualTo("Lilly's SMP");
    }

    @Test
    @DisplayName("with nothing at all it is 'This server', never empty")
    void nothing() {
        assertThat(ServerName.resolve(null, "", null)).isEqualTo(ServerName.UNNAMED);
        assertThat(ServerName.resolve("", "", Component.text("   "))).isEqualTo("This server");
    }

    @Test
    @DisplayName("markup in the setting is kept as written: a name is shown, never parsed")
    void notParsed() {
        assertThat(ServerName.resolve("<red>Lilly", "", null)).isEqualTo("<red>Lilly");
    }
}
