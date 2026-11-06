package de.raindancer.core.world.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("noticing a companion is stuck")
class ProgressTest {

    @Test
    @DisplayName("getting closer is never stuck")
    void closing() {
        Progress progress = new Progress();
        for (int tick = 0; tick < 200; tick++) {
            assertThat(progress.stuck(20 - tick * 0.1, tick, 40)).isFalse();
        }
    }

    @Test
    @DisplayName("no nearer for its patience is stuck; a fresh goal starts over")
    void stuck() {
        Progress progress = new Progress();
        for (int tick = 0; tick <= 40; tick++) {
            assertThat(progress.stuck(10 + Math.sin(tick) * 0.2, tick, 40)).isFalse();
        }
        assertThat(progress.stuck(10, 41, 40)).isTrue();
        assertThat(progress.stuckFor(41)).isEqualTo(41);
        progress.reset();
        assertThat(progress.stuck(10, 42, 40)).isFalse();
    }
}
