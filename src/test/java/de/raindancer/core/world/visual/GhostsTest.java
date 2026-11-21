package de.raindancer.core.world.visual;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The part of a player's ghost blocks that is arithmetic: which to show within the budget, what changed since last time. */
class GhostsTest {

    private static Ghosts.Ghost ghost(int x, int y, int z) {
        return new Ghosts.Ghost(x, y, z, "minecraft:stone", Ghosts.Look.PLANNED);
    }

    @Test
    @DisplayName("over the budget, the ghosts nearest the player are the ones shown")
    void nearestFirst() {
        List<Ghosts.Ghost> wanted = new ArrayList<>();
        for (int x = 0; x < 10; x++) {
            wanted.add(ghost(x, 64, 0));
        }
        List<Ghosts.Ghost> shown = Ghosts.within(wanted, 9.0, 64.0, 0.0, 3);
        assertThat(shown).extracting(Ghosts.Ghost::x).containsExactlyInAnyOrder(9, 8, 7);
    }

    @Test
    @DisplayName("under the budget, everything is shown")
    void allWhenFew() {
        List<Ghosts.Ghost> wanted = List.of(ghost(0, 0, 0), ghost(5, 0, 0));
        assertThat(Ghosts.within(wanted, 0, 0, 0, 10)).hasSize(2);
    }

    @Test
    @DisplayName("an update spawns only what is new and removes only what is gone")
    void diff() {
        Ghosts.Ghost kept = ghost(0, 0, 0);
        Ghosts.Ghost gone = ghost(1, 0, 0);
        Ghosts.Ghost added = ghost(2, 0, 0);
        // The same spot with another look is a different ghost: it has to be drawn again.
        Ghosts.Ghost relooked = new Ghosts.Ghost(0, 0, 0, "minecraft:stone", Ghosts.Look.WRONG);

        Ghosts.Change change = Ghosts.change(Set.of(kept, gone), List.of(kept, added, relooked));
        assertThat(change.spawn()).containsExactlyInAnyOrder(added, relooked);
        assertThat(change.remove()).containsExactly(gone);
    }

    @Test
    @DisplayName("ghosts are grouped by the chunk they stand in, so each group runs on its own region")
    void byChunk() {
        Map<Long, List<Ghosts.Ghost>> grouped = Ghosts.byChunk(List.of(ghost(0, 0, 0), ghost(15, 0, 15),
                ghost(16, 0, 0), ghost(-1, 0, 0)));
        assertThat(grouped).hasSize(3);
        assertThat(grouped.get(Ghosts.chunkKey(0, 0))).hasSize(2);
        assertThat(grouped.get(Ghosts.chunkKey(-1, 0))).hasSize(1);
    }

    @Test
    @DisplayName("the twelve edges of a box, and no point twice at a corner")
    void boxEdges() {
        List<double[]> points = Ghosts.edges(0, 0, 0, 2, 2, 2, 1.0);
        // 2x2x2 box, a point every block: 8 corners + 12 edges × 1 inner point = 20.
        assertThat(points).hasSize(20);
        assertThat(points).allSatisfy(point -> {
            int outside = 0;
            for (double value : point) {
                if (value != 0 && value != 2) {
                    outside++;
                }
            }
            assertThat(outside).as("on an edge, at most one coordinate is between the faces").isLessThanOrEqualTo(1);
        });
    }
}
