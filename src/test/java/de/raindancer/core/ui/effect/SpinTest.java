package de.raindancer.core.ui.effect;

import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("a display turning round its own upright axis")
class SpinTest {

    private static final Transformation BASE = new Transformation(new Vector3f(0, 0.1f, 0), new Quaternionf(),
            new Vector3f(0.45f, 0.45f, 0.45f), new Quaternionf());

    @Test
    @DisplayName("step 0 is the display as it was made; after a whole turn it is there again")
    void wholeTurn() {
        assertThat(facing(Spin.turned(BASE, 0)).x).isCloseTo(1, within(1e-5f));
        Vector3f again = facing(Spin.turned(BASE, Spin.STEPS));
        assertThat(again.x).isCloseTo(1, within(1e-5f));
        assertThat(again.z).isCloseTo(0, within(1e-5f));
    }

    @Test
    @DisplayName("every step turns the same way by the same third of a circle, never the short way back")
    void sameWayEveryStep() {
        for (long step = 0; step < Spin.STEPS * 3L; step++) {
            Quaternionf from = Spin.turned(BASE, step).getLeftRotation();
            Quaternionf to = Spin.turned(BASE, step + 1).getLeftRotation();
            // The client slerps the shortest way: the difference must be the same turn about +Y, under 180°.
            Quaternionf delta = new Quaternionf(to).mul(new Quaternionf(from).invert());
            Vector3f turned = delta.transform(new Vector3f(1, 0, 0));
            double degrees = Math.toDegrees(Math.atan2(-turned.z, turned.x));
            assertThat(degrees).as("step %d", step).isCloseTo(360.0 / Spin.STEPS, within(1e-3));
        }
        assertThat(360.0 / Spin.STEPS).isLessThan(180);
    }

    @Test
    @DisplayName("only the turn changes: where it sits and how big it is stay")
    void keepsTheRest() {
        Transformation turned = Spin.turned(BASE, 2);
        assertThat(turned.getTranslation()).isEqualTo(BASE.getTranslation());
        assertThat(turned.getScale()).isEqualTo(BASE.getScale());
        assertThat(turned.getRightRotation()).isEqualTo(BASE.getRightRotation());
    }

    @Test
    @DisplayName("a step lasts a share of the turn, at least a tick; a turn too quick to see as a spin is refused")
    void stepTicks() {
        assertThat(Spin.stepTicks(120)).isEqualTo(40);
        assertThat(Spin.stepTicks(100)).isEqualTo(33);
        assertThatThrownBy(() -> Spin.stepTicks(Spin.STEPS - 1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Vector3f facing(Transformation transformation) {
        return transformation.getLeftRotation().transform(new Vector3f(1, 0, 0));
    }
}
