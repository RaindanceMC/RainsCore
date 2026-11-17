package de.raindancer.core.ui.statue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatueSpotTest {

    @Test
    @DisplayName("a statue's spot is read and written back the same way, its key with it")
    void roundTrip() {
        StatueSpot spot = StatueSpot.parse("casino 10.5 64 -3.25 45 richest 2 -").orElseThrow();
        assertThat(spot.world()).isEqualTo("casino");
        assertThat(spot.yaw()).isEqualTo(45f);
        assertThat(spot.board()).isEqualTo("richest");
        assertThat(spot.place()).isEqualTo(2);
        assertThat(spot.standalone()).isTrue();
        StatueSpot again = StatueSpot.parse(spot.written()).orElseThrow();
        assertThat(again).isEqualTo(spot);
        assertThat(again.key()).isEqualTo(spot.key()).doesNotContain("#");
        assertThat(StatueSpot.parse("w 0 0 0 0 casino 1 a1b2c3d4").orElseThrow().standalone()).isFalse();
    }

    @Test
    @DisplayName("written exactly as Economy wrote it, so the pieces of a statue it put up are found by the same key")
    void sameKeyAsEconomy() {
        StatueSpot spot = new StatueSpot("world", 1.5, 64, 2.5, 90, "richest", 1, "-");
        assertThat(spot.written()).isEqualTo("world 1.50 64.00 2.50 90.0 richest 1 -");
        assertThat(StatueSpot.parse("world 1.50 64.00 2.50 90.0 RICHEST 1 -")).map(StatueSpot::board)
                .contains("richest");
    }

    @Test
    @DisplayName("broken lines and places past the most are skipped rather than guessed at")
    void broken() {
        assertThat(StatueSpot.parse("w 1 2 3 0 casino 65 -")).isEmpty();
        assertThat(StatueSpot.parse("w 1 2 3 0 casino 0 -")).isEmpty();
        assertThat(StatueSpot.parse("w 1 2 3 0 jack.pot 1 -")).isEmpty();
        assertThat(StatueSpot.parse("w a 2 3 0 casino 1 -")).isEmpty();
        assertThat(StatueSpot.parse("w 1 2 3 0 casino 1")).isEmpty();
        assertThat(StatueSpot.parse(null)).isEmpty();
        assertThat(StatueSpot.parse("spawn 1.00 64.00 2.00 90.0 prime 17 -")).map(StatueSpot::place).contains(17);
    }

    @Test
    @DisplayName("a ranking's place is typed as 1, 2 or 3, a thank-you's up to 64, with or without a #")
    void places() {
        assertThat(StatueSpot.readPlace(false, "1")).contains(1);
        assertThat(StatueSpot.readPlace(false, "#3")).contains(3);
        assertThat(StatueSpot.readPlace(false, "4")).isEmpty();
        assertThat(StatueSpot.readPlace(false, "first")).isEmpty();
        assertThat(StatueSpot.readPlace(false, null)).isEmpty();
        assertThat(StatueSpot.readPlace(true, "64")).contains(64);
        assertThat(StatueSpot.readPlace(true, "#17")).contains(17);
        assertThat(StatueSpot.readPlace(true, "65")).isEmpty();
        assertThat(StatueSpot.readPlace(true, "0")).isEmpty();
    }

    @Test
    @DisplayName("moved somewhere else a statue faces the new way and stands on its own")
    void moved() {
        StatueSpot spot = new StatueSpot("w", 0.5, 64, 0.5, 0, "casino", 1, "a1b2c3d4");
        StatueSpot there = spot.at("nether", 10.5, 70, -2.5, 90);
        assertThat(there).isEqualTo(new StatueSpot("nether", 10.5, 70, -2.5, 90, "casino", 1, "-"));
    }
}
