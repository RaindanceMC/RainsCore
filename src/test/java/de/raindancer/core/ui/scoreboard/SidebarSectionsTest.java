package de.raindancer.core.ui.scoreboard;

import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SidebarSectionsTest {

    private static final UUID ADA = UUID.randomUUID();

    @AfterEach
    void clear() {
        SidebarSections.clear();
    }

    private static List<String> plain(List<Component> lines) {
        return lines.stream().map(Text::plain).toList();
    }

    @Test
    @DisplayName("every section a player has, each after an empty line, in the order registered")
    void sections() {
        SidebarSections.register("guilds", player -> List.of(Component.text("Guild"), Component.text(" Level 3")));
        SidebarSections.register("jobs", player -> List.of());
        SidebarSections.register("other", player -> List.of(Component.text("Other")));
        assertThat(plain(SidebarSections.linesFor(ADA, 10))).containsExactly("", "Guild", " Level 3", "", "Other");
    }

    @Test
    @DisplayName("never more lines than there is room for; a section that does not fit whole is left out")
    void room() {
        SidebarSections.register("guilds", player -> List.of(Component.text("Guild"), Component.text(" Level 3")));
        SidebarSections.register("other", player -> List.of(Component.text("Other")));
        assertThat(plain(SidebarSections.linesFor(ADA, 3))).containsExactly("", "Guild", " Level 3");
        assertThat(SidebarSections.linesFor(ADA, 0)).isEmpty();
    }

    @Test
    @DisplayName("a section that throws is skipped, the sidebar still drawn; unregistering takes it away")
    void failures() {
        SidebarSections.register("broken", player -> {
            throw new IllegalStateException("gone");
        });
        SidebarSections.register("fine", player -> List.of(Component.text("ok")));
        assertThat(plain(SidebarSections.linesFor(ADA, 10))).containsExactly("", "ok");
        SidebarSections.unregister("fine");
        assertThat(SidebarSections.linesFor(ADA, 10)).isEmpty();
        assertThat(SidebarSections.forgetFrom(SidebarSectionsTest.class.getClassLoader())).isEqualTo(1);
    }
}
