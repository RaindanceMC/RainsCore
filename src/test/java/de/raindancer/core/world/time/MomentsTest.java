package de.raindancer.core.world.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class MomentsTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    /** 2026-10-11 14:20:30 in Berlin (summer time, +02:00). */
    private static final Instant NOW = ZonedDateTime.of(2026, 10, 11, 14, 20, 30, 0, BERLIN).toInstant();

    private static Instant berlin(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, BERLIN).toInstant();
    }

    @Test
    @DisplayName("a date with a time is that minute in the zone, written with a space, T, _ or @")
    void dateAndTime() {
        Instant expected = berlin(2026, 10, 10, 17, 30);
        assertThat(Moments.start("2026-10-10 17:30", BERLIN, NOW)).contains(expected);
        assertThat(Moments.start("2026-10-10T17:30", BERLIN, NOW)).contains(expected);
        assertThat(Moments.start("2026-10-10_17:30", BERLIN, NOW)).contains(expected);
        assertThat(Moments.start("10.10.2026@17:30", BERLIN, NOW)).contains(expected);
        assertThat(expected).isEqualTo(Instant.parse("2026-10-10T15:30:00Z"));
    }

    @Test
    @DisplayName("as the end of a range, the whole unit typed is included: the minute, the second, the day")
    void endsIncludeTheUnit() {
        assertThat(Moments.end("2026-10-10 18:00", BERLIN, NOW)).contains(berlin(2026, 10, 10, 18, 1));
        assertThat(Moments.end("2026-10-10 18:00:00", BERLIN, NOW))
                .contains(berlin(2026, 10, 10, 18, 0).plusSeconds(1));
        assertThat(Moments.end("2026-10-10", BERLIN, NOW)).contains(berlin(2026, 10, 11, 0, 0));
        assertThat(Moments.start("2026-10-10", BERLIN, NOW)).contains(berlin(2026, 10, 10, 0, 0));
    }

    @Test
    @DisplayName("today, yesterday, now and a bare time are read against the zone's own calendar")
    void relativeWords() {
        assertThat(Moments.start("today", BERLIN, NOW)).contains(berlin(2026, 10, 11, 0, 0));
        assertThat(Moments.end("today", BERLIN, NOW)).contains(berlin(2026, 10, 12, 0, 0));
        assertThat(Moments.start("Yesterday", BERLIN, NOW)).contains(berlin(2026, 10, 10, 0, 0));
        assertThat(Moments.end("yesterday", BERLIN, NOW)).contains(berlin(2026, 10, 11, 0, 0));
        assertThat(Moments.start("now", BERLIN, NOW)).contains(NOW);
        assertThat(Moments.start("9:05", BERLIN, NOW)).contains(berlin(2026, 10, 11, 9, 5));
        assertThat(Moments.start("10.10.", BERLIN, NOW)).contains(berlin(2026, 10, 10, 0, 0));
    }

    @Test
    @DisplayName("Berlin's day is not UTC's: just after midnight in Berlin is still yesterday in UTC")
    void zoneMatters() {
        Instant justAfterMidnight = berlin(2026, 10, 11, 0, 30);
        Moments.Span today = Moments.day("today", BERLIN, justAfterMidnight).orElseThrow();
        assertThat(today.from()).isEqualTo(Instant.parse("2026-10-10T22:00:00Z"));
        assertThat(today.contains(justAfterMidnight)).isTrue();
        assertThat(today.contains(Instant.parse("2026-10-10T21:59:59Z"))).isFalse();
    }

    @Test
    @DisplayName("the day the clocks go back has 25 hours, all of them in it")
    void daylightSaving() {
        Moments.Span day = Moments.day("2026-10-25", BERLIN, NOW).orElseThrow();
        assertThat(Duration.between(day.from(), day.to())).isEqualTo(Duration.ofHours(25));
    }

    @Test
    @DisplayName("last 2h, 30m, 3d reach back from now; an offset written out is taken as it is")
    void lastAndOffsets() {
        assertThat(Moments.last("2h", NOW)).contains(new Moments.Span(NOW.minus(Duration.ofHours(2)), NOW));
        assertThat(Moments.last("30m", NOW).orElseThrow().from()).isEqualTo(NOW.minusSeconds(1800));
        assertThat(Moments.last("3d", NOW).orElseThrow().from()).isEqualTo(NOW.minus(Duration.ofDays(3)));
        assertThat(Moments.last("forever", NOW)).isEmpty();
        assertThat(Moments.start("2026-10-10T17:30:00+02:00", BERLIN, NOW)).contains(berlin(2026, 10, 10, 17, 30));
    }

    @Test
    @DisplayName("nonsense is refused rather than guessed at")
    void nonsense() {
        assertThat(Moments.start("soon", BERLIN, NOW)).isEmpty();
        assertThat(Moments.start("2026-13-40", BERLIN, NOW)).isEmpty();
        assertThat(Moments.start("25:00", BERLIN, NOW)).isEmpty();
        assertThat(Moments.day("17:30", BERLIN, NOW)).isEmpty();
        assertThat(Moments.start("", BERLIN, NOW)).isEmpty();
        assertThat(Moments.start(null, BERLIN, NOW)).isEmpty();
    }

    @Test
    @DisplayName("written back, a moment reads as the same moment, offset and all")
    void roundTrip() {
        Instant moment = berlin(2026, 10, 25, 2, 30).plusSeconds(3600);   // the second 02:30 that night
        String written = Moments.write(moment, BERLIN);
        assertThat(written).doesNotContain(" ");
        assertThat(Moments.start(written, BERLIN, NOW)).contains(moment);
        assertThat(Moments.show(berlin(2026, 10, 10, 17, 30), BERLIN)).isEqualTo("2026-10-10 17:30");
    }
}
