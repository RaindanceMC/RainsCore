package de.raindancer.core.data.runs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunTextTest {

    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID SAM = UUID.randomUUID();

    @Test
    @DisplayName("times read like a speedrun timer, to the millisecond")
    void clock() {
        assertThat(RunText.clock(Duration.ofMillis(83_456))).isEqualTo("1:23.456");
        assertThat(RunText.clock(Duration.ofMillis(3_723_004))).isEqualTo("1:02:03.004");
        assertThat(RunText.clock(Duration.ofMillis(5_000))).isEqualTo("0:05.000");
        assertThat(RunText.clock(Duration.ZERO)).isEqualTo("0:00.000");
        assertThat(RunText.clock(null)).isEqualTo("-");
    }

    @Test
    @DisplayName("a gap is signed, ahead in the run's own direction")
    void gaps() {
        assertThat(RunText.gap(-1_500, true)).isEqualTo("-1.500");
        assertThat(RunText.gap(61_000, true)).isEqualTo("+1:01.000");
        assertThat(RunText.gap(3, false)).isEqualTo("+3");
        assertThat(RunText.gap(0, true)).isEqualTo("±0.000");
    }

    @Test
    @DisplayName("places and teams read as people say them")
    void words() {
        assertThat(RunText.ordinal(1)).isEqualTo("1st");
        assertThat(RunText.ordinal(2)).isEqualTo("2nd");
        assertThat(RunText.ordinal(3)).isEqualTo("3rd");
        assertThat(RunText.ordinal(11)).isEqualTo("11th");
        assertThat(RunText.ordinal(22)).isEqualTo("22nd");
        assertThat(RunText.ordinal(113)).isEqualTo("113th");
        Run duo = Run.timed("speedrun/duo", Duration.ofMinutes(20)).player(ALEX, "Alex").player(SAM, "Sam").build();
        assertThat(RunText.who(duo)).isEqualTo("Alex & Sam");
        assertThat(RunText.who(Run.timed("x", Duration.ZERO).build())).isEqualTo("nobody");
    }

    @Test
    @DisplayName("a score is a time for a timed run and a number otherwise")
    void score() {
        assertThat(RunText.score(Run.timed("x", Duration.ofSeconds(90)).build())).isEqualTo("1:30.000");
        assertThat(RunText.score(Run.scored("x", 12, false).build())).isEqualTo("12");
    }

    @Test
    @DisplayName("board lines: place, who, score; the reader's own runs marked; names never markup")
    void boardLines() {
        Run first = Run.timed("x", Duration.ofSeconds(60)).player(ALEX, "<red>Alex").build();
        Run second = Run.timed("x", Duration.ofSeconds(75)).player(SAM, "Sam").build();

        List<String> lines = RunText.boardLines(List.of(first, second), SAM, RunText::score);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("1st").contains("\\<red>Alex").contains("1:00.000");
        assertThat(lines.get(1)).contains("2nd").contains("Sam").contains("you");
        assertThat(RunText.boardLines(List.of(), SAM, RunText::score)).containsExactly("<gray>No runs yet.");
    }
}
