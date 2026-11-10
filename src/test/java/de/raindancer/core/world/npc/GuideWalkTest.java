package de.raindancer.core.world.npc;

import de.raindancer.core.world.npc.GuideWalk.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("a guide walking a way, with the player in tow")
class GuideWalkTest {

    private static final List<Point> WAY = List.of(new Point(0, 64, 0), new Point(4, 64, 0), new Point(4, 65, 3));

    @Test
    @DisplayName("steps along the way at its speed, corner by corner, and is there at the end")
    void walks() {
        GuideWalk walk = new GuideWalk(WAY, 0.5, 8);
        Point first = walk.step(1);
        assertThat(first.x()).isCloseTo(0.5, within(1e-9));
        for (int i = 0; i < 7; i++) {
            walk.step(1);
        }
        assertThat(walk.at().x()).isCloseTo(4, within(1e-9));
        assertThat(walk.done()).isFalse();
        for (int i = 0; i < 20; i++) {
            walk.step(1);
        }
        assertThat(walk.done()).isTrue();
        assertThat(walk.at()).isEqualTo(new Point(4, 65, 3));
    }

    @Test
    @DisplayName("waits while the player is too far behind, and goes on once they catch up")
    void waits() {
        GuideWalk walk = new GuideWalk(WAY, 0.5, 8);
        walk.step(1);
        Point waiting = walk.step(12);
        assertThat(waiting).isEqualTo(walk.at());
        assertThat(walk.waiting()).isTrue();
        assertThat(walk.step(12).x()).isCloseTo(0.5, within(1e-9));
        assertThat(walk.step(3).x()).isCloseTo(1.0, within(1e-9));
        assertThat(walk.waiting()).isFalse();
    }

    @Test
    @DisplayName("faces the way it walks: +x is yaw -90, +z is yaw 0")
    void faces() {
        GuideWalk walk = new GuideWalk(WAY, 0.5, 8);
        walk.step(1);
        assertThat(walk.yaw()).isCloseTo(-90f, within(1e-3f));
        for (int i = 0; i < 9; i++) {
            walk.step(1);
        }
        assertThat(walk.yaw()).isCloseTo(0f, within(1e-3f));
    }

    @Test
    @DisplayName("a way of one point is walked at once")
    void single() {
        GuideWalk walk = new GuideWalk(List.of(new Point(1, 2, 3)), 0.5, 8);
        assertThat(walk.done()).isTrue();
    }

    @Test
    @DisplayName("the yaw to look at somebody")
    void lookAt() {
        assertThat(GuideWalk.yawTowards(new Point(0, 0, 0), new Point(0, 0, 5))).isCloseTo(0f, within(1e-3f));
        assertThat(GuideWalk.yawTowards(new Point(0, 0, 0), new Point(-5, 0, 0))).isCloseTo(90f, within(1e-3f));
    }
}
