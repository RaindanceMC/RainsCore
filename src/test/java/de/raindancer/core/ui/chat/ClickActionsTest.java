package de.raindancer.core.ui.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry behind a clickable chat button.
 *
 * <h2>Why a registry at all</h2>
 * A chat component can only ask the client to <em>type a command</em>. So a button that runs some
 * server-side code needs a command to point at, and the alternatives to this are both bad: invent a
 * command per feature ({@code /townaccept <id>}, {@code /claimfeeaccept <id>}, …), which is how a
 * server ends up with forty commands nobody typed on purpose; or put the action's arguments in the
 * command text, which means a player can read them out of their own chat and type a different set.
 *
 * <p>So a callback is registered here, bound to an opaque token and to the player who is allowed to
 * click it, and the button runs one shared command. Guessing another player's token is guessing a
 * random UUID.
 *
 * <p>These are the rules that make that safe, and none of them need a server to test.
 */
class ClickActionsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private AtomicLong clock;
    private ClickActions actions;
    private List<UUID> ran;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000L);
        actions = new ClickActions(clock::get);
        ran = new ArrayList<>();
    }

    private String register(UUID onlyFor, Duration validFor, boolean oneShot) {
        return actions.register(onlyFor, validFor, oneShot, ran::add);
    }

    // ------------------------------------------------------------------ running one

    @Nested
    @DisplayName("clicking")
    class Clicking {

        @Test
        @DisplayName("the player the button was made for runs it")
        void theOwnerCanClick() {
            String token = register(ALICE, Duration.ofMinutes(5), true);
            assertThat(actions.run(ALICE, token)).isEqualTo(ClickResult.RAN);
            assertThat(ran).containsExactly(ALICE);
        }

        @Test
        @DisplayName("anybody else is refused, even with the right token")
        void nobodyElseCan() {
            String token = register(ALICE, Duration.ofMinutes(5), true);
            assertThat(actions.run(BOB, token)).isEqualTo(ClickResult.NOT_YOURS);
            assertThat(ran).isEmpty();

            // ...and refusing Bob must not have consumed Alice's button.
            assertThat(actions.run(ALICE, token)).isEqualTo(ClickResult.RAN);
        }

        @Test
        @DisplayName("a button with no owner is for whoever clicks it")
        void anUnboundButtonIsPublic() {
            String token = register(null, Duration.ofMinutes(5), false);
            assertThat(actions.run(ALICE, token)).isEqualTo(ClickResult.RAN);
            assertThat(actions.run(BOB, token)).isEqualTo(ClickResult.RAN);
            assertThat(ran).containsExactly(ALICE, BOB);
        }

        @Test
        @DisplayName("a token nobody registered is unknown, not an error")
        void unknownTokensAreRefused() {
            assertThat(actions.run(ALICE, "not-a-token")).isEqualTo(ClickResult.UNKNOWN);
            assertThat(actions.run(ALICE, null)).isEqualTo(ClickResult.UNKNOWN);
            assertThat(actions.run(ALICE, "")).isEqualTo(ClickResult.UNKNOWN);
            assertThat(actions.run(null, "whatever")).isEqualTo(ClickResult.UNKNOWN);
        }
    }

    // ------------------------------------------------------------------ using it up

    @Nested
    @DisplayName("one-shot buttons")
    class OneShot {

        @Test
        @DisplayName("work once — a second click is spent, not run again")
        void areSpentAfterOneClick() {
            String token = register(ALICE, Duration.ofMinutes(5), true);
            assertThat(actions.run(ALICE, token)).isEqualTo(ClickResult.RAN);
            assertThat(actions.run(ALICE, token)).isEqualTo(ClickResult.SPENT);
            assertThat(ran)
                    .as("double-clicking [Accept] must not accept twice")
                    .containsExactly(ALICE);
        }

        @Test
        @DisplayName("stay spent rather than becoming unknown, so the player is told what happened")
        void reportSpentRatherThanUnknown() {
            String token = register(ALICE, Duration.ofMinutes(5), true);
            actions.run(ALICE, token);
            // "You already answered that" is a better message than "that button has expired".
            assertThat(actions.run(ALICE, token)).isEqualTo(ClickResult.SPENT);
        }

        @Test
        @DisplayName("a repeatable button runs every time")
        void repeatableOnesKeepWorking() {
            String token = register(ALICE, Duration.ofMinutes(5), false);
            actions.run(ALICE, token);
            actions.run(ALICE, token);
            actions.run(ALICE, token);
            assertThat(ran).hasSize(3);
        }
    }

    // ------------------------------------------------------------------ expiry

    @Nested
    @DisplayName("expiry")
    class Expiry {

        @Test
        @DisplayName("a button stops working once its time is up")
        void expires() {
            String token = register(ALICE, Duration.ofMinutes(5), true);
            clock.addAndGet(Duration.ofMinutes(6).toMillis());
            assertThat(actions.run(ALICE, token)).isEqualTo(ClickResult.EXPIRED);
            assertThat(ran).isEmpty();
        }

        @Test
        @DisplayName("it works right up to the moment it expires")
        void livesUntilItDoesNot() {
            String token = register(ALICE, Duration.ofMinutes(5), true);
            clock.addAndGet(Duration.ofMinutes(5).toMillis() - 1);
            assertThat(actions.run(ALICE, token)).isEqualTo(ClickResult.RAN);
        }

        @Test
        @DisplayName("expired buttons are swept, so an idle server does not grow for ever")
        void sweepsExpiredTokens() {
            IntStream.range(0, 50).forEach(each -> register(ALICE, Duration.ofMinutes(5), true));
            assertThat(actions.size()).isEqualTo(50);

            clock.addAndGet(Duration.ofMinutes(6).toMillis());
            actions.sweep();

            assertThat(actions.size()).isZero();
        }

        /**
         * Note the asymmetry, which is deliberate rather than an oversight.
         *
         * <p>Clicked after it expired but before the sweep, a button can still say
         * {@link ClickResult#EXPIRED} — see {@link #expires()} — because the record is still there
         * to say so. Once the sweep has run there is nothing left to distinguish it from a token
         * that never existed, and keeping every dead token around for ever just to word the refusal
         * better is not a trade worth making. So the message a player sees for
         * {@link ClickResult#UNKNOWN} has to read sensibly for "that button is no longer good".
         */
        @Test
        @DisplayName("a sweep leaves the buttons that are still good, and forgets the rest entirely")
        void sweepKeepsTheLiveOnes() {
            String shortLived = register(ALICE, Duration.ofMinutes(1), true);
            String longLived = register(ALICE, Duration.ofHours(1), true);

            clock.addAndGet(Duration.ofMinutes(2).toMillis());
            actions.sweep();

            assertThat(actions.run(ALICE, shortLived)).isEqualTo(ClickResult.UNKNOWN);
            assertThat(actions.run(ALICE, longLived)).isEqualTo(ClickResult.RAN);
        }
    }

    // ------------------------------------------------------------------ housekeeping

    @Nested
    @DisplayName("housekeeping")
    class Housekeeping {

        @Test
        @DisplayName("a player who logs out takes their unclicked buttons with them")
        void forgetsAPlayersButtons() {
            String alices = register(ALICE, Duration.ofHours(1), true);
            String bobs = register(BOB, Duration.ofHours(1), true);

            actions.forget(ALICE);

            assertThat(actions.run(ALICE, alices)).isEqualTo(ClickResult.UNKNOWN);
            assertThat(actions.run(BOB, bobs))
                    .as("one player leaving must not take another's buttons")
                    .isEqualTo(ClickResult.RAN);
        }

        @Test
        @DisplayName("an unbound button survives a player logging out — it was not theirs")
        void keepsPublicButtons() {
            String token = register(null, Duration.ofHours(1), false);
            actions.forget(ALICE);
            assertThat(actions.run(ALICE, token)).isEqualTo(ClickResult.RAN);
        }

        @Test
        @DisplayName("a plugin can revoke a button it no longer means")
        void canRevoke() {
            String token = register(ALICE, Duration.ofHours(1), true);
            actions.revoke(token);
            assertThat(actions.run(ALICE, token)).isEqualTo(ClickResult.UNKNOWN);
        }

        @Test
        @DisplayName("tokens are unguessable and never repeat")
        void tokensAreUnique() {
            Set<String> seen = new HashSet<>();
            for (int each = 0; each < 5_000; each++) {
                seen.add(register(ALICE, Duration.ofHours(1), true));
            }
            assertThat(seen).hasSize(5_000);
            assertThat(seen).allMatch(token -> token.length() >= 22,
                    "a token short enough to guess is a token somebody will guess");
        }

        @Test
        @DisplayName("a flood of buttons is bounded rather than eating the heap")
        void isBounded() {
            for (int each = 0; each < ClickActions.MAX_PENDING + 500; each++) {
                register(ALICE, Duration.ofHours(1), true);
            }
            assertThat(actions.size()).isLessThanOrEqualTo(ClickActions.MAX_PENDING);
        }

        @Test
        @DisplayName("when the bound is hit the oldest go first, so a fresh button still works")
        void evictsTheOldest() {
            String oldest = register(ALICE, Duration.ofHours(1), true);
            for (int each = 0; each < ClickActions.MAX_PENDING; each++) {
                register(ALICE, Duration.ofHours(1), true);
            }
            String newest = register(ALICE, Duration.ofHours(1), true);

            assertThat(actions.run(ALICE, oldest)).isEqualTo(ClickResult.UNKNOWN);
            assertThat(actions.run(ALICE, newest)).isEqualTo(ClickResult.RAN);
        }

        @Test
        @DisplayName("a full registry drops lapsed buttons before any live one, whatever their age")
        void lapsedGoBeforeLive() {
            String oldestLive = register(ALICE, Duration.ofHours(1), true);
            for (int each = 0; each < ClickActions.MAX_PENDING - 2; each++) {
                register(ALICE, Duration.ofMinutes(30), true);
            }
            register(ALICE, Duration.ofSeconds(1), true);
            clock.addAndGet(2_000);

            String fresh = register(ALICE, Duration.ofHours(1), true);

            assertThat(actions.run(ALICE, oldestLive)).isEqualTo(ClickResult.RAN);
            assertThat(actions.run(ALICE, fresh)).isEqualTo(ClickResult.RAN);
            assertThat(actions.size()).isLessThanOrEqualTo(ClickActions.MAX_PENDING);
        }

        @Test
        @DisplayName("registering at the cap stays cheap: no scan of every entry per button")
        void evictionIsCheap() {
            for (int each = 0; each < ClickActions.MAX_PENDING; each++) {
                register(ALICE, Duration.ofHours(1), true);
            }
            long start = System.nanoTime();
            for (int each = 0; each < 200_000; each++) {
                register(ALICE, Duration.ofHours(1), true);
            }
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
                    .isLessThan(Duration.ofSeconds(3));
            assertThat(actions.size()).isEqualTo(ClickActions.MAX_PENDING);
        }

        @Test
        @DisplayName("revoked and spent tokens do not pile up in the bookkeeping")
        void revokedDoNotLeak() {
            for (int each = 0; each < 50_000; each++) {
                actions.revoke(register(ALICE, Duration.ofHours(1), true));
            }
            assertThat(actions.size()).isZero();
        }
    }

    // ------------------------------------------------------------------ misbehaviour

    @Nested
    @DisplayName("misbehaviour")
    class Misbehaviour {

        @Test
        @DisplayName("an action that throws still counts as spent, so it cannot be retried for ever")
        void aThrowingActionIsStillSpent() {
            String token = actions.register(ALICE, Duration.ofHours(1), true, clicker -> {
                throw new IllegalStateException("the town was disbanded while you were reading");
            });
            assertThat(actions.run(ALICE, token)).isEqualTo(ClickResult.FAILED);
            assertThat(actions.run(ALICE, token)).isEqualTo(ClickResult.SPENT);
        }

        @Test
        @DisplayName("registering without an action is refused rather than making a dead button")
        void refusesANullAction() {
            assertThat(actions.register(ALICE, Duration.ofHours(1), true, null)).isNull();
            assertThat(actions.size()).isZero();
        }

        @Test
        @DisplayName("a button with no lifetime gets the default rather than living for ever")
        void givesADefaultLifetime() {
            String token = actions.register(ALICE, null, true, ran::add);
            clock.addAndGet(ClickActions.DEFAULT_LIFETIME.toMillis() + 1);
            assertThat(actions.run(ALICE, token)).isEqualTo(ClickResult.EXPIRED);
        }
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("a one-shot button clicked by many threads at once runs exactly once")
    void isSafeFromEveryThread() throws Exception {
        for (int round = 0; round < 200; round++) {
            setUp();
            AtomicInteger runs = new AtomicInteger();
            String token = actions.register(null, Duration.ofHours(1), true,
                    clicker -> runs.incrementAndGet());

            int threads = 8;
            CountDownLatch go = new CountDownLatch(1);
            List<ClickResult> results = java.util.Collections.synchronizedList(new ArrayList<>());
            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                for (int thread = 0; thread < threads; thread++) {
                    pool.submit(() -> {
                        go.await();
                        results.add(actions.run(ALICE, token));
                        return null;
                    });
                }
                go.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(runs.get())
                    .as("round %d: eight clients spamming [Accept] must accept once", round)
                    .isEqualTo(1);
            assertThat(results).filteredOn(ClickResult.RAN::equals).hasSize(1);
        }
    }
}
