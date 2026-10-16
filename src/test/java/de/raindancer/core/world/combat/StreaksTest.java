package de.raindancer.core.world.combat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("kill streaks and what an announcer calls them")
class StreaksTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final UUID CAROL = UUID.nameUUIDFromBytes("carol".getBytes());

    private final AtomicLong now = new AtomicLong(1_000_000);
    private Streaks streaks;

    @BeforeEach
    void setUp() {
        streaks = new Streaks(now::get, Duration.ofSeconds(4));
    }

    @Test
    @DisplayName("the first kill of a round is first blood, and only that one")
    void firstBlood() {
        assertThat(streaks.killed(ALICE, BOB).callouts()).contains(Streaks.Callout.FIRST_BLOOD);
        now.addAndGet(10_000);
        assertThat(streaks.killed(BOB, CAROL).callouts()).doesNotContain(Streaks.Callout.FIRST_BLOOD);
    }

    @Test
    @DisplayName("kills close together are a double, then a triple, then a multi kill")
    void multiKills() {
        streaks.killed(ALICE, BOB);
        now.addAndGet(2_000);
        assertThat(streaks.killed(ALICE, CAROL).callouts()).contains(Streaks.Callout.DOUBLE_KILL);
        now.addAndGet(2_000);
        assertThat(streaks.killed(ALICE, BOB).callouts()).contains(Streaks.Callout.TRIPLE_KILL);
        now.addAndGet(2_000);
        assertThat(streaks.killed(ALICE, CAROL).callouts()).contains(Streaks.Callout.MULTI_KILL);
    }

    @Test
    @DisplayName("a kill after the window has passed starts a new run of quick kills")
    void windowPasses() {
        streaks.killed(ALICE, BOB);
        now.addAndGet(5_000);
        assertThat(streaks.killed(ALICE, CAROL).callouts()).doesNotContain(Streaks.Callout.DOUBLE_KILL);
    }

    @Test
    @DisplayName("a streak is named at three, five, seven and ten kills without dying")
    void streakNames() {
        Streaks.Kill last = null;
        for (int kill = 1; kill <= 10; kill++) {
            now.addAndGet(60_000);
            last = streaks.killed(ALICE, kill % 2 == 0 ? BOB : CAROL);
            switch (kill) {
                case 3 -> assertThat(last.callouts()).contains(Streaks.Callout.KILLING_SPREE);
                case 5 -> assertThat(last.callouts()).contains(Streaks.Callout.RAMPAGE);
                case 7 -> assertThat(last.callouts()).contains(Streaks.Callout.UNSTOPPABLE);
                case 10 -> assertThat(last.callouts()).contains(Streaks.Callout.GODLIKE);
                default -> assertThat(last.callouts()).doesNotContain(Streaks.Callout.KILLING_SPREE,
                        Streaks.Callout.RAMPAGE, Streaks.Callout.UNSTOPPABLE, Streaks.Callout.GODLIKE);
            }
        }
        assertThat(last.streak()).isEqualTo(10);
    }

    @Test
    @DisplayName("ending somebody's streak of three or more is a shutdown, and their streak is gone")
    void shutdown() {
        for (int kill = 0; kill < 3; kill++) {
            now.addAndGet(60_000);
            streaks.killed(ALICE, CAROL);
        }
        now.addAndGet(60_000);
        assertThat(streaks.killed(BOB, ALICE).callouts()).contains(Streaks.Callout.SHUTDOWN);
        assertThat(streaks.streakOf(ALICE)).isZero();
    }

    @Test
    @DisplayName("dying without a killer ends the streak quietly")
    void diedAlone() {
        streaks.killed(ALICE, BOB);
        streaks.died(ALICE);
        assertThat(streaks.streakOf(ALICE)).isZero();
    }

    @Test
    @DisplayName("killing yourself counts for nothing")
    void selfKill() {
        Streaks.Kill kill = streaks.killed(ALICE, ALICE);
        assertThat(kill.streak()).isZero();
        assertThat(kill.callouts()).isEmpty();
    }

    @Test
    @DisplayName("a reset makes the next kill first blood again and forgets everybody")
    void reset() {
        streaks.killed(ALICE, BOB);
        streaks.reset();
        assertThat(streaks.streakOf(ALICE)).isZero();
        assertThat(streaks.killed(BOB, ALICE).callouts()).contains(Streaks.Callout.FIRST_BLOOD);
    }

    @Test
    @DisplayName("the best streak of the round is remembered after it ends")
    void best() {
        for (int kill = 0; kill < 4; kill++) {
            now.addAndGet(60_000);
            streaks.killed(ALICE, BOB);
        }
        streaks.killed(BOB, ALICE);
        assertThat(streaks.bestOf(ALICE)).isEqualTo(4);
    }
}
