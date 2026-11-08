package de.raindancer.core.ui.scoreboard;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SidebarSizesTest {

    private static final UUID ADA = UUID.randomUUID();
    private static final String TOP = "test-richest";
    private static final String BIG = "test-big";

    @AfterEach
    void clear() {
        SidebarSections.unregister(TOP);
        SidebarSections.unregister(BIG);
    }

    private static List<Component> lines(String heading, int count) {
        List<Component> out = new ArrayList<>();
        out.add(Component.text(heading));
        for (int i = 1; i <= count; i++) {
            out.add(Component.text(heading + " " + i));
        }
        return out;
    }

    private static void richest() {
        SidebarSections.registerSized(TOP, "Richest", 90, new SidebarSections.Size(1, 10, 3, "players"),
                (player, count) -> lines("Richest", count));
    }

    @Test
    @DisplayName("a player's sizes are kept with the rest of their layout, and survive moving and hiding")
    void kept() {
        SidebarLayout mine = SidebarLayout.DEFAULT.sized(TOP, 5);
        assertThat(mine.isDefault()).isFalse();
        assertThat(mine.sizeOf(TOP, 3)).isEqualTo(5);
        assertThat(mine.sizeOf(BIG, 3)).as("not chosen: the section's own").isEqualTo(3);
        assertThat(SidebarLayout.fromLine(mine.asLine())).isEqualTo(mine);
        assertThat(mine.toggled(TOP).sizeOf(TOP, 3)).isEqualTo(5);
        assertThat(SidebarLayout.fromLine("order:a;hidden:b")).as("a line from before sizes")
                .isEqualTo(new SidebarLayout(List.of("a"), java.util.Set.of("b")));
    }

    @Test
    @DisplayName("a sized section shows as many as the player chose")
    void chosen() {
        richest();
        assertThat(SidebarSections.compose(ADA, SidebarLayout.DEFAULT, 15)).hasSize(4);
        assertThat(SidebarSections.compose(ADA, SidebarLayout.DEFAULT.sized(TOP, 7), 15)).hasSize(8);
    }

    @Test
    @DisplayName("short of room it is made smaller to fit, rather than left out — and the menu is told")
    void shrinks() {
        SidebarSections.register(BIG, "Big", 10, player -> lines("Big", 9));
        richest();
        List<Component> lines = SidebarSections.compose(ADA, SidebarLayout.DEFAULT, 15);
        // 10 for Big, a gap, then Richest in the 4 left: heading and 3 of them
        assertThat(lines).hasSize(15);
        assertThat(lines.getLast()).isEqualTo(Component.text("Richest 3"));

        Map<String, SidebarSections.Fit> fits = SidebarSections.fits(ADA, SidebarLayout.DEFAULT.sized(TOP, 8), 15);
        assertThat(fits.get(BIG)).isEqualTo(SidebarSections.Fit.SHOWN);
        assertThat(fits.get(TOP)).isEqualTo(SidebarSections.Fit.SHORTENED);
    }

    @Test
    @DisplayName("what does not fit even at its smallest is left out, and the menu says so")
    void noRoom() {
        SidebarSections.register(BIG, "Big", 10, player -> lines("Big", 12));
        richest();
        Map<String, SidebarSections.Fit> fits = SidebarSections.fits(ADA, SidebarLayout.DEFAULT, 15);
        assertThat(fits.get(TOP)).isEqualTo(SidebarSections.Fit.NO_ROOM);
        assertThat(SidebarSections.fits(ADA, SidebarLayout.DEFAULT.toggled(TOP), 15).get(TOP))
                .isEqualTo(SidebarSections.Fit.HIDDEN);
    }

    @Test
    @DisplayName("a size outside the section's own range is brought into it")
    void clamped() {
        richest();
        assertThat(SidebarSections.compose(ADA, SidebarLayout.DEFAULT.sized(TOP, 50), 15)).hasSize(11);
        assertThat(SidebarSections.compose(ADA, SidebarLayout.DEFAULT.sized(TOP, 0), 15)).hasSize(2);
    }
}
