package de.raindancer.core.world.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("a flyer keeping a sensible height")
class FlightTest {

    @Test
    @DisplayName("too near the ground, it lifts")
    void lifts() {
        Steering.Vec push = Flight.keepHeight(new Steering.Vec(0.3, -0.1, 0), 0.4, 10, 0.3);
        assertThat(push.y()).isGreaterThan(0);
        assertThat(push.x()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("under a ceiling, it climbs no further")
    void ceiling() {
        Steering.Vec push = Flight.keepHeight(new Steering.Vec(0, 0.3, 0), 3, 0.4, 0.3);
        assertThat(push.y()).isLessThanOrEqualTo(0);
    }

    @Test
    @DisplayName("high over the ground, it does not climb further into the sky")
    void sky() {
        Steering.Vec push = Flight.keepHeight(new Steering.Vec(0.2, 0.1, 0), Flight.HIGHEST + 1, 10, 0.3);
        assertThat(push.y()).isLessThan(0);
    }

    @Test
    @DisplayName("in between, it flies as it was going to")
    void free() {
        Steering.Vec wanted = new Steering.Vec(0.2, 0.05, 0.1);
        assertThat(Flight.keepHeight(wanted, 3, 5, 0.3)).isEqualTo(wanted);
    }
}
