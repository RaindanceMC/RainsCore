package de.raindancer.core.world.movement;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("jump pads")
class JumpPadsTest {

    @Test
    @DisplayName("a pad throws you the way you look, flat, and up")
    void launch() {
        Vector south = JumpPads.launch(0, 2.0, 1.0);
        assertThat(south.getZ()).isCloseTo(2.0, within(1e-9));
        assertThat(south.getX()).isCloseTo(0.0, within(1e-9));
        assertThat(south.getY()).isCloseTo(1.0, within(1e-9));

        Vector west = JumpPads.launch(90, 1.5, 0.8);
        assertThat(west.getX()).isCloseTo(-1.5, within(1e-9));
        assertThat(west.getZ()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("a launch is capped, so a pad set to silly numbers cannot fling anybody out of the world")
    void capped() {
        Vector wild = JumpPads.launch(45, 100, 100);
        assertThat(wild.getY()).isLessThanOrEqualTo(JumpPads.MOST_UP);
        assertThat(Math.hypot(wild.getX(), wild.getZ())).isLessThanOrEqualTo(JumpPads.MOST_FORWARD + 1e-9);
    }

    @Test
    @DisplayName("the landing after a launch does not hurt, for a while")
    void softLanding() {
        long[] now = {0};
        SoftLandings landings = new SoftLandings(() -> now[0]);
        java.util.UUID alice = java.util.UUID.randomUUID();
        landings.launched(alice);
        now[0] = 5_000;
        assertThat(landings.spared(alice)).isTrue();
        assertThat(landings.spared(alice)).as("one landing").isFalse();
        landings.launched(alice);
        now[0] += JumpPads.SOFT_LANDING_MILLIS + 1;
        assertThat(landings.spared(alice)).as("too long ago").isFalse();
    }
}
