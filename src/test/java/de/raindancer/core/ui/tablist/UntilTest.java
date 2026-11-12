package de.raindancer.core.ui.tablist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("<until:…> counts down to a moment")
class UntilTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final long LAUNCH = Instant.parse("2026-11-21T15:00:00Z").toEpochMilli();

    private static long before(long days, long hours, long minutes) {
        return LAUNCH - ((days * 24 + hours) * 60 + minutes) * 60_000;
    }

    @Test
    @DisplayName("days, hours and minutes while it is still ahead")
    void countsDown() {
        assertThat(Until.fill("Opening in <until:2026-11-21T16:00>", before(8, 16, 3), BERLIN))
                .isEqualTo("Opening in 8d 16h 3m");
        assertThat(Until.fill("<until:2026-11-21T16:00>", before(0, 2, 1), BERLIN)).isEqualTo("2h 1m");
    }

    @Test
    @DisplayName("rounds up, so it never says 0 while still ahead")
    void roundsUp() {
        assertThat(Until.fill("<until:2026-11-21T16:00>", LAUNCH - 1, BERLIN)).isEqualTo("1m");
        assertThat(Until.fill("<until:2026-11-21T16:00>", LAUNCH - 60_001, BERLIN)).isEqualTo("2m");
    }

    @Test
    @DisplayName("a time with an offset means the same moment whatever zone the server runs in")
    void honoursAnOffset() {
        ZoneId utc = ZoneId.of("UTC");
        assertThat(Until.fill("<until:2026-11-21T16:00+01:00>", before(0, 0, 5), utc)).isEqualTo("5m");
        assertThat(Until.fill("<until:2026-11-21T15:00Z>", before(0, 0, 5), utc)).isEqualTo("5m");
        // without one it is the server's own time
        assertThat(Until.fill("<until:2026-11-21T16:00>", before(0, 0, 5), utc)).isEqualTo("1h 5m");
    }

    @Test
    @DisplayName("once the moment has passed: the text after the bar, or nothing")
    void afterwards() {
        assertThat(Until.fill("<until:2026-11-21T16:00|We're open!>", LAUNCH, BERLIN)).isEqualTo("We're open!");
        assertThat(Until.fill("Opening <until:2026-11-21T16:00>", LAUNCH + 1, BERLIN)).isEqualTo("Opening ");
        assertThat(Until.fill("<until:2026-11-21T16:00|We're open!>", LAUNCH - 1, BERLIN)).isEqualTo("1m");
    }

    @Test
    @DisplayName("several in one text, and text without any is left alone")
    void severalAndNone() {
        assertThat(Until.fill("<until:2026-11-21T16:00> / <until:2026-11-21T17:00>", before(0, 0, 30), BERLIN))
                .isEqualTo("30m / 1h 30m");
        assertThat(Until.fill("<gold>Hyades</gold>", LAUNCH, BERLIN)).isEqualTo("<gold>Hyades</gold>");
    }

    @Test
    @DisplayName("a moment nobody can read stays as written, so the owner sees the typo")
    void keepsWhatItCannotRead() {
        assertThat(Until.fill("<until:tomorrow>", LAUNCH, BERLIN)).isEqualTo("<until:tomorrow>");
    }
}
