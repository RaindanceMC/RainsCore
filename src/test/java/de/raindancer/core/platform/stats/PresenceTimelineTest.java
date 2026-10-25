package de.raindancer.core.platform.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Players online at once, replayed from the journal's joins and quits")
class PresenceTimelineTest {

    private static final long MIN = 60_000;
    private final UUID ada = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final UUID cy = UUID.randomUUID();

    private static PresenceTimeline.Event join(long at, UUID who) {
        return new PresenceTimeline.Event(at, who, true);
    }

    private static PresenceTimeline.Event quit(long at, UUID who) {
        return new PresenceTimeline.Event(at, who, false);
    }

    @Test
    @DisplayName("counts who is on, and the most at once with when")
    void peak() {
        PresenceTimeline timeline = PresenceTimeline.of(List.of(join(0, ada), join(MIN, bo), join(2 * MIN, cy),
                quit(3 * MIN, ada), quit(4 * MIN, bo)));
        assertThat(timeline.peak()).isEqualTo(new StatsHistory.Record(3, 2 * MIN));
        assertThat(timeline.onlineAt(3 * MIN + 1)).isEqualTo(2);
        assertThat(timeline.onlineAt(10 * MIN)).isEqualTo(1);
    }

    @Test
    @DisplayName("a server switch is no leaving: joined the next before quitting the last, or the other way round")
    void switches() {
        PresenceTimeline joinFirst = PresenceTimeline.of(List.of(join(0, ada), join(10 * MIN, ada),
                quit(10 * MIN + 500, ada)));
        assertThat(joinFirst.onlineAt(11 * MIN)).isEqualTo(1);
        assertThat(joinFirst.peak().players()).isEqualTo(1);
        PresenceTimeline quitFirst = PresenceTimeline.of(List.of(join(0, ada), quit(10 * MIN, ada),
                join(10 * MIN + 2_000, ada)));
        assertThat(quitFirst.onlineAt(11 * MIN)).isEqualTo(1);
    }

    @Test
    @DisplayName("a join long after one whose quit was lost starts afresh: nobody counts twice or stays on for ever")
    void lostQuit() {
        PresenceTimeline timeline = PresenceTimeline.of(List.of(join(0, ada), join(300 * MIN, ada),
                quit(301 * MIN, ada)));
        assertThat(timeline.onlineAt(302 * MIN)).isZero();
        assertThat(timeline.peak().players()).isEqualTo(1);
    }

    @Test
    @DisplayName("columns hold the most on at once in each stretch, carried in from before it")
    void columns() {
        PresenceTimeline timeline = PresenceTimeline.of(List.of(join(0, ada), join(5 * MIN, bo), quit(6 * MIN, bo)));
        double[] most = timeline.columns(4 * MIN, 12 * MIN, 2);
        assertThat(most[0]).isEqualTo(2);
        assertThat(most[1]).isEqualTo(1);
    }

    @Test
    @DisplayName("visitors: everybody who joined since a moment, each once")
    void visitors() {
        PresenceTimeline timeline = PresenceTimeline.of(List.of(join(0, ada), join(5 * MIN, bo), quit(6 * MIN, bo),
                join(7 * MIN, bo)));
        assertThat(timeline.visitorsSince(4 * MIN)).isEqualTo(1);
        assertThat(timeline.visitorsSince(0)).isEqualTo(2);
    }
}
