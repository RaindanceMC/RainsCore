package de.raindancer.core.world.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("a companion exploring while its owner is idle")
class WanderingTest {

    private static final Steering.Vec HOME = new Steering.Vec(10, 64, 10);

    @Test
    @DisplayName("an owner is idle once they have hardly moved for a while")
    void idle() {
        Wandering wandering = new Wandering();
        assertThat(wandering.ownerIdle(HOME, 0)).isFalse();
        for (int tick = 1; tick <= Wandering.IDLE_AFTER; tick++) {
            wandering.ownerIdle(new Steering.Vec(10.1, 64, 10), tick);
        }
        assertThat(wandering.ownerIdle(HOME, Wandering.IDLE_AFTER + 1)).isTrue();
        assertThat(wandering.ownerIdle(new Steering.Vec(14, 64, 10), Wandering.IDLE_AFTER + 2)).isFalse();
    }

    @Test
    @DisplayName("spots to explore lie within the radius; a flyer's in the air above, a walker's on the ground")
    void spots() {
        Random random = new Random(3);
        for (int i = 0; i < 200; i++) {
            Steering.Vec walk = Wandering.spot(HOME, 8, false, random);
            assertThat(Math.hypot(walk.x() - HOME.x(), walk.z() - HOME.z())).isLessThanOrEqualTo(8);
            assertThat(walk.y()).isEqualTo(HOME.y());
            Steering.Vec fly = Wandering.spot(HOME, 8, true, random);
            assertThat(fly.y()).isBetween(HOME.y() + 1, HOME.y() + 5);
        }
    }

    @Test
    @DisplayName("an eager explorer sets off again sooner than a reluctant one, and never without radius")
    void eagerness() {
        Random random = new Random(1);
        int eager = 0;
        int reluctant = 0;
        for (int i = 0; i < 2000; i++) {
            if (Wandering.setsOff(1.0, 0.0 + 8, random)) {
                eager++;
            }
            if (Wandering.setsOff(0.1, 8, random)) {
                reluctant++;
            }
        }
        assertThat(eager).isGreaterThan(reluctant * 3);
        assertThat(Wandering.setsOff(1.0, 0, random)).isFalse();
    }

    @Test
    @DisplayName("the spec carries how far and how eagerly")
    void spec() {
        CompanionSpec spec = CompanionSpec.of(org.bukkit.entity.EntityType.WOLF).wander(9, 0.7);
        assertThat(spec.wanderRadius()).isEqualTo(9);
        assertThat(spec.wanderEagerness()).isEqualTo(0.7);
        assertThat(CompanionSpec.of(org.bukkit.entity.EntityType.WOLF).wander(-2, 3).wanderEagerness()).isEqualTo(1);
    }
}
