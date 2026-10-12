package de.raindancer.core.ui.tablist;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The server's own health under the list: ticks per second, milliseconds per tick, memory. */
class TablistPerformanceTest {

    @TempDir
    Path folder;

    private TablistModel model() {
        return new TablistModel(new Identities(Database.open(
                folder.resolve("identities.db"), CoreSchema.CORE, () -> false)));
    }

    @Test
    @DisplayName("one line: TPS to one decimal, capped at 20, the tick in ms, memory in GB")
    void line() {
        Component line = model().performance(new ServerLoad(20.03, 31.4, 3_174, 6_144));
        assertThat(Text.plain(line)).isEqualTo("TPS 20.0 · 31 ms · RAM 3.1/6.0 GB");
    }

    @Test
    @DisplayName("colours say how it is going: green, yellow, red")
    void colours() {
        assertThat(ServerLoad.tpsColour(19.8)).isEqualTo(NamedTextColor.GREEN);
        assertThat(ServerLoad.tpsColour(17.5)).isEqualTo(NamedTextColor.YELLOW);
        assertThat(ServerLoad.tpsColour(12)).isEqualTo(NamedTextColor.RED);
        assertThat(ServerLoad.msptColour(30)).isEqualTo(NamedTextColor.GREEN);
        assertThat(ServerLoad.msptColour(45)).isEqualTo(NamedTextColor.YELLOW);
        assertThat(ServerLoad.msptColour(70)).isEqualTo(NamedTextColor.RED);
    }

    @Test
    @DisplayName("an owner's own header or footer can say <tps>, <mspt> and <ram>")
    void placeholders() {
        Component custom = model().custom("<gray>TPS <tps> · <mspt> · <ram>", List.of(), "Lilly",
                new ServerLoad(18.456, 49.6, 1_024, 4_096));
        assertThat(Text.plain(custom)).isEqualTo("TPS 18.5 · 50 ms · 1.0/4.0 GB");
        assertThat(Text.plain(model().custom("<tps>", List.of(), "Lilly", null))).isEqualTo("?");
    }
}
