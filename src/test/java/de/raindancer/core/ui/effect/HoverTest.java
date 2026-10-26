package de.raindancer.core.ui.effect;

import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("a display bobbing up and down like a dropped item, turning or not")
class HoverTest {

    private static final Transformation BASE = new Transformation(new Vector3f(0, 0.2f, 0), new Quaternionf(),
            new Vector3f(0.45f, 0.45f, 0.45f), new Quaternionf());

    @Test
    @DisplayName("it rises and sinks by its height round where it was made, once every bob")
    void bobs() {
        Hover hover = new Hover(0, 0.1f);
        float lowest = Float.MAX_VALUE;
        float highest = -Float.MAX_VALUE;
        for (long tick = 0; tick <= Hover.BOB_TICKS; tick++) {
            float y = hover.at(BASE, tick).getTranslation().y;
            lowest = Math.min(lowest, y);
            highest = Math.max(highest, y);
        }
        assertThat(highest).isCloseTo(0.3f, within(1e-4f));
        assertThat(lowest).isCloseTo(0.1f, within(1e-4f));
        assertThat(hover.at(BASE, Hover.BOB_TICKS).getTranslation().y).isCloseTo(0.2f, within(1e-4f));
        assertThat(hover.at(BASE, 17).getLeftRotation()).as("not turning").isEqualTo(BASE.getLeftRotation());
    }

    @Test
    @DisplayName("turning and bobbing together: steps short enough for a sine, every one turning forwards")
    void both() {
        Hover hover = new Hover(120, 0.1f);
        assertThat(hover.stepTicks()).isEqualTo(Hover.BOB_TICKS / 6);
        for (long step = 0; step < 40; step++) {
            Quaternionf from = hover.at(BASE, step * hover.stepTicks()).getLeftRotation();
            Quaternionf to = hover.at(BASE, (step + 1) * hover.stepTicks()).getLeftRotation();
            Vector3f turned = new Quaternionf(to).mul(new Quaternionf(from).invert()).transform(new Vector3f(1, 0, 0));
            double degrees = Math.toDegrees(Math.atan2(-turned.z, turned.x));
            assertThat(degrees).as("step %d", step).isCloseTo(360.0 * hover.stepTicks() / 120, within(1e-2));
        }
        Vector3f once = hover.at(BASE, 120).getLeftRotation().transform(new Vector3f(1, 0, 0));
        assertThat(once.x).as("a whole turn in 120 ticks").isCloseTo(1, within(1e-4f));
    }

    @Test
    @DisplayName("a quick turn gets shorter steps, so none is half a turn or more")
    void quickTurn() {
        assertThat(new Hover(21, 0.1f).stepTicks()).isEqualTo(7);
        assertThat(new Hover(120, 0).stepTicks()).as("turning only: thirds").isEqualTo(40);
    }

    @Test
    @DisplayName("standing still with no bob is no motion at all, and a negative one is refused")
    void nothing() {
        assertThat(new Hover(0, 0).moves()).isFalse();
        assertThat(new Hover(0, 0.1f).moves()).isTrue();
        assertThatThrownBy(() -> new Hover(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Hover(2, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Hover(0, -0.1f)).isInstanceOf(IllegalArgumentException.class);
    }
}
