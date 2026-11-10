package de.raindancer.core.ui.camera;

import de.raindancer.core.ui.camera.CameraPath.Frame;
import de.raindancer.core.ui.camera.CameraPath.Key;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("a camera path: a smooth glide through keyframes")
class CameraPathTest {

    private static final List<Key> KEYS = List.of(
            new Key(0, 70, 0, 0, 10, "The spawn"),
            new Key(20, 75, 0, 90, 20, "The market"),
            new Key(20, 80, 30, 170, 0, "The harbour"),
            new Key(0, 72, 40, -170, -10, ""));

    private final CameraPath path = CameraPath.through(KEYS, 5);

    @Test
    @DisplayName("as long as the gaps between its keys")
    void seconds() {
        assertThat(path.seconds()).isEqualTo(15.0);
        assertThat(CameraPath.through(List.of(KEYS.getFirst()), 5).seconds()).isZero();
    }

    @Test
    @DisplayName("starts on the first key and ends on the last, looking as they look")
    void ends() {
        Frame start = path.at(0);
        Frame end = path.at(15);
        assertThat(start.x()).isCloseTo(0, within(1e-9));
        assertThat(start.y()).isCloseTo(70, within(1e-9));
        assertThat(start.pitch()).isCloseTo(10f, within(1e-4f));
        assertThat(end.x()).isCloseTo(0, within(1e-9));
        assertThat(end.z()).isCloseTo(40, within(1e-9));
        assertThat(CameraPath.wrap(end.yaw())).isCloseTo(-170f, within(1e-3f));
        assertThat(path.at(99).z()).as("past the end it stays at the end").isCloseTo(40, within(1e-9));
    }

    @Test
    @DisplayName("passes through every key on the way")
    void throughKeys() {
        for (int i = 0; i < KEYS.size(); i++) {
            Frame at = path.at(path.secondsAt(i));
            assertThat(at.x()).as("key %d x", i).isCloseTo(KEYS.get(i).x(), within(1e-6));
            assertThat(at.z()).as("key %d z", i).isCloseTo(KEYS.get(i).z(), within(1e-6));
        }
    }

    @Test
    @DisplayName("never jumps: a twentieth of a second moves the camera only a little, and turns it only a little")
    void smooth() {
        Frame last = path.at(0);
        for (double t = 0.05; t <= path.seconds(); t += 0.05) {
            Frame now = path.at(t);
            double moved = Math.sqrt(Math.pow(now.x() - last.x(), 2) + Math.pow(now.y() - last.y(), 2)
                    + Math.pow(now.z() - last.z(), 2));
            assertThat(moved).as("moved at %.2f s", t).isLessThan(1.0);
            assertThat(Math.abs(now.yaw() - last.yaw())).as("turned at %.2f s", t).isLessThan(8f);
            last = now;
        }
    }

    @Test
    @DisplayName("eases in and out: slow at the start and the end, faster in between")
    void eases() {
        double startStep = distance(path.at(0), path.at(0.1));
        double middleStep = distance(path.at(7.5), path.at(7.6));
        double endStep = distance(path.at(14.9), path.at(15));
        assertThat(startStep).isLessThan(middleStep / 4);
        assertThat(endStep).isLessThan(middleStep / 4);
    }

    @Test
    @DisplayName("turns the short way round: from 170 to -170 through 180, never back through 0")
    void shortWayRound() {
        double tHarbour = path.secondsAt(2);
        double tLast = path.secondsAt(3);
        for (double t = tHarbour; t <= tLast; t += 0.1) {
            float yaw = Math.abs(CameraPath.wrap(path.at(t).yaw()));
            assertThat(yaw).as("at %.1f s", t).isGreaterThan(160f);
        }
    }

    @Test
    @DisplayName("which key was reached last, for its caption")
    void reached() {
        assertThat(path.keyReached(0)).isZero();
        assertThat(path.keyReached(path.secondsAt(1) + 0.01)).isEqualTo(1);
        assertThat(path.keyReached(path.secondsAt(1) - 0.01)).isZero();
        assertThat(path.keyReached(15)).isEqualTo(3);
    }

    @Test
    @DisplayName("needs a key, and some time between keys")
    void refuses() {
        assertThatThrownBy(() -> CameraPath.through(List.of(), 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CameraPath.through(KEYS, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static double distance(Frame a, Frame b) {
        return Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2));
    }
}
