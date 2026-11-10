package de.raindancer.core.ui.camera;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("a cutscene's timeline: where to send the camera, and when")
class CutsceneTimelineTest {

    private static final CameraPath PATH = CameraPath.through(List.of(
            new CameraPath.Key(0, 70, 0, 0, 0, "Hello"),
            new CameraPath.Key(10, 70, 0, 0, 0, ""),
            new CameraPath.Key(10, 70, 10, 0, 0, "There")), 4);

    private final CutsceneTimeline timeline = new CutsceneTimeline(PATH, 10, 1.5);

    @Test
    @DisplayName("the camera is sent one segment ahead, every segment, so the client glides it there in time")
    void ahead() {
        assertThat(timeline.sendAt(0)).isTrue();
        assertThat(timeline.sendAt(5)).isFalse();
        assertThat(timeline.sendAt(10)).isTrue();
        assertThat(timeline.targetSeconds(0)).isCloseTo(0.5, within(1e-9));
        assertThat(timeline.targetSeconds(10)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("it is over once the path has run and the last shot was held a moment")
    void over() {
        assertThat(timeline.over(159)).isFalse();
        assertThat(timeline.over(190)).isTrue();
    }

    @Test
    @DisplayName("a caption when its key is reached, once, and none for a blank one")
    void captions() {
        assertThat(timeline.captionAt(0)).contains(0);
        assertThat(timeline.captionAt(1)).isEmpty();
        int there = (int) Math.ceil(PATH.secondsAt(2) * 20);
        boolean shown = false;
        for (int tick = 2; tick <= there + 1; tick++) {
            var caption = timeline.captionAt(tick);
            if (caption.isPresent()) {
                assertThat(caption).as("only key 2 has a caption left").contains(2);
                shown = true;
            }
        }
        assertThat(shown).isTrue();
    }

    @Test
    @DisplayName("a segment must fit the client's interpolation: 1 to 59 ticks")
    void segment() {
        assertThatThrownBy(() -> new CutsceneTimeline(PATH, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CutsceneTimeline(PATH, 60, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
