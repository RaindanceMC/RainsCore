package de.raindancer.core.ui.scoreboard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SidebarLayoutTest {

    private static final List<SidebarLayout.Known> KNOWN = List.of(
            new SidebarLayout.Known("economy-balance", 0), new SidebarLayout.Known("guild", 30),
            new SidebarLayout.Known("economy-lottery", 60), new SidebarLayout.Known("economy-richest", 90));

    @Test
    @DisplayName("by default the sections come by rank, all shown")
    void theDefaultIsTheServersOrder() {
        assertThat(SidebarLayout.DEFAULT.arrange(KNOWN))
                .containsExactly("economy-balance", "guild", "economy-lottery", "economy-richest");
        assertThat(SidebarLayout.DEFAULT.shows("guild")).isTrue();
        assertThat(SidebarLayout.DEFAULT.isDefault()).isTrue();
    }

    @Test
    @DisplayName("what the player ordered comes first as they put it, the rest by rank")
    void theirOrderComesFirst() {
        SidebarLayout mine = new SidebarLayout(List.of("economy-richest", "guild"), Set.of());
        assertThat(mine.arrange(KNOWN)).containsExactly("economy-richest", "guild", "economy-balance", "economy-lottery");
    }

    @Test
    @DisplayName("a section nobody registers any more is simply skipped in the order")
    void aGoneSectionIsSkipped() {
        SidebarLayout mine = new SidebarLayout(List.of("pets", "guild"), Set.of());
        assertThat(mine.arrange(KNOWN)).startsWith("guild");
    }

    @Test
    @DisplayName("moving up and down swaps with the neighbour and stops at the ends")
    void movingUpAndDown() {
        SidebarLayout moved = SidebarLayout.DEFAULT.movedUp("economy-lottery", KNOWN);
        assertThat(moved.arrange(KNOWN)).containsExactly("economy-balance", "economy-lottery", "guild", "economy-richest");
        assertThat(moved.movedDown("economy-lottery", KNOWN).arrange(KNOWN))
                .containsExactly("economy-balance", "guild", "economy-lottery", "economy-richest");
        assertThat(SidebarLayout.DEFAULT.movedUp("economy-balance", KNOWN)).isSameAs(SidebarLayout.DEFAULT);
        assertThat(SidebarLayout.DEFAULT.movedDown("economy-richest", KNOWN)).isSameAs(SidebarLayout.DEFAULT);
        assertThat(SidebarLayout.DEFAULT.movedUp("nobody", KNOWN)).isSameAs(SidebarLayout.DEFAULT);
    }

    @Test
    @DisplayName("hiding is a toggle and leaves the order alone")
    void hiding() {
        SidebarLayout hidden = SidebarLayout.DEFAULT.toggled("guild");
        assertThat(hidden.shows("guild")).isFalse();
        assertThat(hidden.shows("economy-balance")).isTrue();
        assertThat(hidden.toggled("guild").shows("guild")).isTrue();
        assertThat(hidden.reset().isDefault()).isTrue();
    }

    @Test
    @DisplayName("a layout survives being written as one line and read back")
    void roundTrip() {
        SidebarLayout mine = new SidebarLayout(List.of("guild", "economy-balance"), Set.of("economy-lottery"));
        SidebarLayout back = SidebarLayout.fromLine(mine.asLine());
        assertThat(back.order()).containsExactly("guild", "economy-balance");
        assertThat(back.hidden()).containsExactly("economy-lottery");
        assertThat(SidebarLayout.fromLine(null).isDefault()).isTrue();
        assertThat(SidebarLayout.fromLine("order:;hidden:").isDefault()).isTrue();
        assertThat(SidebarLayout.fromLine("rubbish").isDefault()).isTrue();
    }
}
