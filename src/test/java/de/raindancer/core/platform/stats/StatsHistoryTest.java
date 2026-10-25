package de.raindancer.core.platform.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("The server's last day, minute by minute, and its records")
class StatsHistoryTest {

    private static StatsHistory.Sample at(long minute, double tps, int players) {
        return new StatsHistory.Sample(minute, tps, 10, 512, 2048, 30, players);
    }

    @Test
    @DisplayName("keeps a day of minutes; the oldest go, a minute measured twice counts once")
    void keepsADay() {
        StatsHistory history = new StatsHistory();
        for (long minute = 0; minute < StatsHistory.MINUTES + 10; minute++) {
            history.add(at(minute, 20, 1));
        }
        history.add(at(StatsHistory.MINUTES + 9, 15, 4));
        assertThat(history.samples()).hasSize(StatsHistory.MINUTES);
        assertThat(history.samples().getFirst().minute()).isEqualTo(10);
        assertThat(history.samples().getLast().tps()).isEqualTo(15);
    }

    @Test
    @DisplayName("columns of a chart: the average or the highest of each stretch; nothing measured is NaN")
    void columns() {
        StatsHistory history = new StatsHistory();
        history.add(at(0, 20, 2));
        history.add(at(1, 10, 6));
        history.add(at(4, 18, 3));
        double[] avg = history.columns(StatsHistory.Sample::tps, 0, 6, 3, StatsHistory.Mode.AVERAGE);
        assertThat(avg[0]).isEqualTo(15);
        assertThat(avg[1]).isNaN();
        assertThat(avg[2]).isEqualTo(18);
        double[] most = history.columns(sample -> sample.players(), 0, 6, 3, StatsHistory.Mode.HIGHEST);
        assertThat(most[0]).isEqualTo(6);
    }

    @Test
    @DisplayName("the record of players at once only ever goes up, and remembers when")
    void record() {
        StatsHistory history = new StatsHistory();
        history.online(5, 1_000);
        history.online(3, 2_000);
        history.online(5, 3_000);
        assertThat(history.record()).isEqualTo(new StatsHistory.Record(5, 1_000));
        history.online(8, 4_000);
        assertThat(history.record()).isEqualTo(new StatsHistory.Record(8, 4_000));
    }

    @Test
    @DisplayName("players today: each counted once, a new day starts at none")
    void today() {
        StatsHistory history = new StatsHistory();
        UUID ada = UUID.randomUUID();
        LocalDate monday = LocalDate.of(2026, 10, 26);
        history.joined(ada, monday);
        history.joined(ada, monday);
        history.joined(UUID.randomUUID(), monday);
        assertThat(history.playersOn(monday)).isEqualTo(2);
        history.joined(ada, monday.plusDays(1));
        assertThat(history.playersOn(monday.plusDays(1))).isEqualTo(1);
        assertThat(history.playersOn(monday)).as("yesterday is gone").isZero();
    }

    @Test
    @DisplayName("written to lines and read back the same; garbled lines are left out")
    void lines() {
        StatsHistory history = new StatsHistory();
        history.add(new StatsHistory.Sample(7, 19.5, 12.25, 900, 4096, 41.5, 3));
        history.add(new StatsHistory.Sample(8, Double.NaN, Double.NaN, 900, 4096, Double.NaN, 3));
        StatsHistory read = new StatsHistory();
        history.lines().forEach(read::read);
        read.read("nonsense");
        assertThat(read.samples()).isEqualTo(history.samples());
    }
}
