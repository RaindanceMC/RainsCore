package de.raindancer.core.world.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("how a pet moves")
class SteeringTest {

    private static final Steering.Vec ORIGIN = new Steering.Vec(0, 64, 0);

    @Test
    @DisplayName("close enough is standing still, sideways only — gravity keeps its say on the ground")
    void arrived() {
        Steering.Vec push = Steering.towards(ORIGIN, new Steering.Vec(1, 64, 1), 0.3, 2.5, false, -0.08);
        assertThat(push.x()).isZero();
        assertThat(push.z()).isZero();
        assertThat(push.y()).isEqualTo(-0.08);
    }

    @Test
    @DisplayName("a walking pet heads for the target at its speed and keeps falling")
    void walking() {
        Steering.Vec push = Steering.towards(ORIGIN, new Steering.Vec(10, 70, 0), 0.3, 2.5, false, -0.1);
        assertThat(push.x()).isEqualTo(0.3, within(1e-9));
        assertThat(push.z()).isEqualTo(0, within(1e-9));
        assertThat(push.y()).isEqualTo(-0.1);
    }

    @Test
    @DisplayName("a flying pet heads there in three dimensions")
    void flying() {
        Steering.Vec push = Steering.towards(ORIGIN, new Steering.Vec(0, 74, 0), 0.4, 2.5, true, 0);
        assertThat(push.y()).isEqualTo(0.4, within(1e-9));
        assertThat(Math.hypot(push.x(), push.z())).isEqualTo(0, within(1e-9));
    }

    @Test
    @DisplayName("ridden forward, it goes where the rider looks; strafing goes sideways")
    void ridden() {
        Steering.Input forward = new Steering.Input(true, false, false, false, false);
        Steering.Vec push = Steering.ridden(forward, 0, 0, 0.5, false, -0.08, false);
        assertThat(push.z()).isEqualTo(0.5, within(1e-9));
        assertThat(push.x()).isEqualTo(0, within(1e-9));
        assertThat(push.y()).isEqualTo(-0.08);

        Steering.Vec west = Steering.ridden(forward, 90, 0, 0.5, false, 0, false);
        assertThat(west.x()).isEqualTo(-0.5, within(1e-9));

        Steering.Input left = new Steering.Input(false, false, true, false, false);
        Steering.Vec strafe = Steering.ridden(left, 0, 0, 0.5, false, 0, false);
        assertThat(strafe.x()).isGreaterThan(0);
    }

    @Test
    @DisplayName("a walker jumps only from the ground")
    void jumping() {
        Steering.Input jump = new Steering.Input(false, false, false, false, true);
        assertThat(Steering.ridden(jump, 0, 0, 0.5, false, -0.08, true).y()).isEqualTo(Steering.JUMP);
        assertThat(Steering.ridden(jump, 0, 0, 0.5, false, -0.08, false).y()).isEqualTo(-0.08);
    }

    @Test
    @DisplayName("a flyer climbs on jump, follows the rider's pitch forward, and hovers otherwise")
    void flyingRidden() {
        Steering.Input jump = new Steering.Input(false, false, false, false, true);
        assertThat(Steering.ridden(jump, 0, 0, 0.5, true, 0, false).y()).isGreaterThan(0);

        Steering.Input forward = new Steering.Input(true, false, false, false, false);
        Steering.Vec diving = Steering.ridden(forward, 0, 45, 0.5, true, 0, false);
        assertThat(diving.y()).isLessThan(0);
        assertThat(diving.z()).isGreaterThan(0);

        Steering.Vec hover = Steering.ridden(new Steering.Input(false, false, false, false, false), 0, 0, 0.5, true, 0, false);
        assertThat(hover.length()).isZero();
    }

    @Test
    @DisplayName("the yaw a mob faces when it moves along a push")
    void yaw() {
        assertThat((double) Steering.yawOf(new Steering.Vec(0, 0, 1))).isEqualTo(0, within(1e-6));
        assertThat((double) Steering.yawOf(new Steering.Vec(-1, 0, 0))).isEqualTo(90, within(1e-6));
    }
}
