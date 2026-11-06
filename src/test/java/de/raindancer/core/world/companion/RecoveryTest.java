package de.raindancer.core.world.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("a companion getting its strength back")
class RecoveryTest {

    @Test
    @DisplayName("it mends only after a quiet while, and only near its owner")
    void mends() {
        long calm = Recovery.CALM_TICKS;
        assertThat(Recovery.mends(calm, 25, 0.5)).isEqualTo(0.5);
        assertThat(Recovery.mends(calm - 1, 25, 0.5)).isZero();
        assertThat(Recovery.mends(calm, Recovery.RANGE * Recovery.RANGE + 1, 0.5)).isZero();
        assertThat(Recovery.mends(calm * 10, 0, 0)).isZero();
    }

    @Test
    @DisplayName("hurt, it is less lively; whole, fully")
    void vigour() {
        assertThat(Recovery.vigour(1)).isEqualTo(1);
        assertThat(Recovery.vigour(0.5)).isBetween(0.7, 0.85);
        assertThat(Recovery.vigour(0.05)).isBetween(0.5, 0.6);
        assertThat(Recovery.vigour(0.3)).isLessThan(Recovery.vigour(0.6));
    }

    @Test
    @DisplayName("hurt, it keeps close enough to its owner to mend, and explores only that far")
    void staysNear() {
        assertThat(Recovery.roam(20, 0.5, 0.5)).isLessThan(Recovery.RANGE - 1);
        assertThat(Recovery.roam(20, 1, 0.5)).isEqualTo(20);
        assertThat(Recovery.roam(20, 0.5, 0)).isEqualTo(20);
        assertThat(Recovery.roam(4, 0.5, 0.5)).isEqualTo(4);
    }
}
