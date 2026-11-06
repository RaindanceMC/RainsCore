package de.raindancer.core.world.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("companions keeping out of each other's way")
class SpacingTest {

    private static final Steering.Vec OWNER = new Steering.Vec(0, 64, 0);

    private static double flat(Steering.Vec a, Steering.Vec b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("each of an owner's companions has its own spot beside them, well apart from the others")
    void ownSpots(int count) {
        List<Steering.Vec> spots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            spots.add(Spacing.spot(OWNER, 0, i, count, Spacing.FOLLOW));
        }
        for (int i = 0; i < count; i++) {
            assertThat(flat(spots.get(i), OWNER)).isEqualTo(Spacing.FOLLOW, within(0.01));
            for (int j = i + 1; j < count; j++) {
                assertThat(flat(spots.get(i), spots.get(j))).as(i + " and " + j).isGreaterThan(1.3);
            }
        }
    }

    @Test
    @DisplayName("the spots are behind the owner, the way they face")
    void behind() {
        // yaw 0 faces +z
        for (int i = 0; i < 3; i++) {
            assertThat(Spacing.spot(OWNER, 0, i, 3, Spacing.FOLLOW).z()).isLessThan(0);
        }
        Steering.Vec alone = Spacing.spot(OWNER, 90, 0, 1, Spacing.FOLLOW);
        assertThat(alone.x()).isGreaterThan(1.5);
    }

    @Test
    @DisplayName("two on top of each other are pushed apart, opposite ways")
    void apart() {
        Steering.Vec a = new Steering.Vec(0, 64, 0);
        Steering.Vec b = new Steering.Vec(0.3, 64, 0);
        Steering.Vec pushA = Spacing.away(a, List.of(b), 1.0, 0);
        Steering.Vec pushB = Spacing.away(b, List.of(a), 1.0, 1);
        assertThat(pushA.x()).isLessThan(0);
        assertThat(pushB.x()).isGreaterThan(0);
        assertThat(pushA.y()).isZero();
    }

    @Test
    @DisplayName("nobody near, no push; and the push is never wild")
    void gentle() {
        Steering.Vec far = Spacing.away(OWNER, List.of(new Steering.Vec(3, 64, 0)), 1.0, 0);
        assertThat(far.length()).isZero();
        Steering.Vec crowded = Spacing.away(OWNER, List.of(new Steering.Vec(0.1, 64, 0), new Steering.Vec(0, 64, 0.1),
                new Steering.Vec(-0.1, 64, 0.1)), 1.0, 0);
        assertThat(crowded.length()).isLessThanOrEqualTo(Spacing.MOST + 1e-9);
    }

    @Test
    @DisplayName("exactly on the same spot, each still gets a way out, and not the same one")
    void sameSpot() {
        Steering.Vec a = Spacing.away(OWNER, List.of(OWNER), 1.0, 0);
        Steering.Vec b = Spacing.away(OWNER, List.of(OWNER), 1.0, 1);
        assertThat(a.length()).isGreaterThan(0);
        assertThat(a.x() * b.x() + a.z() * b.z()).isLessThan(0);
    }

    @Test
    @DisplayName("a flyer hovering just over a walker counts as on top of it")
    void stacked() {
        Steering.Vec walker = new Steering.Vec(0, 64, 0);
        Steering.Vec flyer = new Steering.Vec(0.2, 65.5, 0);
        assertThat(Spacing.away(flyer, List.of(walker), 1.0, 0).x()).isGreaterThan(0);
        Steering.Vec high = new Steering.Vec(0.2, 70, 0);
        assertThat(Spacing.away(high, List.of(walker), 1.0, 0).length()).isZero();
    }

    @Test
    @DisplayName("a spot to explore is not picked right where another companion is or is going")
    void exploring() {
        Random random = new Random(9);
        List<Steering.Vec> taken = List.of(new Steering.Vec(1, 64, 1), new Steering.Vec(-2, 64, 0));
        for (int i = 0; i < 200; i++) {
            Steering.Vec spot = Spacing.freeSpot(OWNER, 4, false, taken, random);
            for (Steering.Vec other : taken) {
                assertThat(flat(spot, other)).isGreaterThanOrEqualTo(Spacing.APART - 1e-9);
            }
        }
    }
}
