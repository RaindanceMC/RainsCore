package de.raindancer.core.world.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("a companion at rest")
class RestingTest {

    private static Resting.Pose after(long still, double vigour, boolean canLie, long seed) {
        Random random = new Random(seed);
        Resting.Pose pose = Resting.Pose.STAND;
        for (long t = 0; t <= still; t += 20) {
            pose = Resting.next(pose, t, vigour, canLie, random);
        }
        return pose;
    }

    @Test
    @DisplayName("just come to a stop, it stands")
    void fresh() {
        assertThat(Resting.next(Resting.Pose.STAND, 40, 1, true, new Random(1))).isEqualTo(Resting.Pose.STAND);
    }

    @Test
    @DisplayName("a long while still, it sits, and later lies down where it can")
    void settles() {
        int sat = 0;
        int lay = 0;
        for (long seed = 0; seed < 50; seed++) {
            Resting.Pose pose = after(2400, 1, true, seed);
            sat += pose != Resting.Pose.STAND ? 1 : 0;
            lay += pose == Resting.Pose.LIE ? 1 : 0;
            assertThat(after(2400, 1, false, seed)).isNotEqualTo(Resting.Pose.LIE);
        }
        assertThat(sat).isGreaterThan(30);
        assertThat(lay).isGreaterThan(10);
    }

    @Test
    @DisplayName("tired, it sits down sooner")
    void tired() {
        int lively = 0;
        int weary = 0;
        for (long seed = 0; seed < 200; seed++) {
            lively += after(300, 1, true, seed) != Resting.Pose.STAND ? 1 : 0;
            weary += after(300, 0.5, true, seed) != Resting.Pose.STAND ? 1 : 0;
        }
        assertThat(weary).isGreaterThan(lively);
    }

    @Test
    @DisplayName("a flyer at rest bobs gently instead of hanging still")
    void hover() {
        double a = Resting.bob(0, 0.3);
        double b = Resting.bob(10, 0.3);
        assertThat(a).isNotEqualTo(b);
        for (long t = 0; t < 200; t++) {
            assertThat(Math.abs(Resting.bob(t, 0.3))).isLessThanOrEqualTo(0.05);
        }
    }
}
