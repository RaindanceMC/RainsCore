package de.raindancer.core.moderation.players;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class PingHistoryTest {

    private final UUID ada = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private final UUID bo = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final PingHistory history = new PingHistory(now::get);

    private void every5s(UUID player, String name, int... pings) {
        for (int ping : pings) {
            history.record(player, name, ping);
            now.addAndGet(5_000);
        }
    }

    @Test
    @DisplayName("nobody measured has no summary")
    void nothing() {
        assertThat(history.summary(ada, Duration.ofMinutes(15))).isEmpty();
    }

    @Test
    @DisplayName("a summary has the latest, lowest, average, median, 95th percentile, highest and jitter")
    void figures() {
        every5s(ada, "Ada", 100, 300, 200, 400, 1000);
        PingHistory.Summary s = history.summary(ada, Duration.ofMinutes(15)).orElseThrow();
        assertThat(s.name()).isEqualTo("Ada");
        assertThat(s.samples()).isEqualTo(5);
        assertThat(s.latest()).isEqualTo(1000);
        assertThat(s.min()).isEqualTo(100);
        assertThat(s.average()).isEqualTo(400);
        assertThat(s.median()).isEqualTo(300);
        assertThat(s.p95()).isEqualTo(1000);
        assertThat(s.max()).isEqualTo(1000);
        // |300-100| + |200-300| + |400-200| + |1000-400| = 1100, over 4 steps
        assertThat(s.jitter()).isEqualTo(275);
        assertThat(s.covered()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("not measured yet (a negative ping) is not a sample")
    void unmeasured() {
        every5s(ada, "Ada", -1, 0, 50);
        PingHistory.Summary s = history.summary(ada, Duration.ofMinutes(15)).orElseThrow();
        assertThat(s.samples()).isEqualTo(2);
        assertThat(s.min()).isZero();
    }

    @Test
    @DisplayName("only the asked window counts")
    void window() {
        every5s(ada, "Ada", 900, 900, 900);
        now.addAndGet(Duration.ofMinutes(10).toMillis());
        every5s(ada, "Ada", 100, 100);
        PingHistory.Summary recent = history.summary(ada, Duration.ofMinutes(1)).orElseThrow();
        assertThat(recent.samples()).isEqualTo(2);
        assertThat(recent.max()).isEqualTo(100);
        assertThat(history.summary(ada, Duration.ofMinutes(15)).orElseThrow().samples()).isEqualTo(5);
    }

    @Test
    @DisplayName("an hour is kept, older samples go")
    void keepsAnHour() {
        every5s(ada, "Ada", 999);
        now.addAndGet(PingHistory.KEPT.toMillis());
        every5s(ada, "Ada", 50);
        PingHistory.Summary s = history.summary(ada, Duration.ofHours(5)).orElseThrow();
        assertThat(s.samples()).isEqualTo(1);
        assertThat(s.max()).isEqualTo(50);
    }

    @Test
    @DisplayName("somebody who left is still there by name until their last sample is an hour old")
    void afterLeaving() {
        every5s(ada, "Hydr0Drag0n", 300, 320);
        now.addAndGet(Duration.ofMinutes(30).toMillis());
        assertThat(history.recentlySeen("hydr0drag0n")).contains(ada);
        assertThat(history.summary(ada, Duration.ofMinutes(15))).isEmpty();
        assertThat(history.summary(ada, Duration.ofMinutes(60))).isPresent();
        now.addAndGet(Duration.ofMinutes(31).toMillis());
        history.prune();
        assertThat(history.recentlySeen("Hydr0Drag0n")).isEmpty();
        assertThat(history.summary(ada, Duration.ofMinutes(60))).isEmpty();
    }

    @Test
    @DisplayName("the latest name wins when somebody renames")
    void renamed() {
        every5s(ada, "Old", 40);
        every5s(ada, "New", 40);
        assertThat(history.recentlySeen("Old")).isEmpty();
        assertThat(history.recentlySeen("new")).contains(ada);
        assertThat(history.recentlySeen("Bo")).isEmpty();
        every5s(bo, "Bo", 40);
        assertThat(history.recentlySeen("bo")).contains(bo);
    }

    @Test
    @DisplayName("columns average each slice of the window, oldest first; a slice without samples is -1")
    void columns() {
        // 60 s window, 3 columns of 20 s: samples at 0..55 s → 4 per column
        every5s(ada, "Ada", 10, 10, 10, 10, 50, 50, 50, 50, 90, 90, 90, 90);
        PingHistory.Summary s = history.summary(ada, Duration.ofSeconds(60)).orElseThrow();
        assertThat(s.columns(3)).containsExactly(10, 50, 90);

        now.addAndGet(Duration.ofSeconds(40).toMillis());
        PingHistory.Summary later = history.summary(ada, Duration.ofSeconds(60)).orElseThrow();
        assertThat(later.columns(3)).containsExactly(90, -1, -1);
    }
}
