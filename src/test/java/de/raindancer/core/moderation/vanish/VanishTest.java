package de.raindancer.core.moderation.vanish;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Being properly not here.
 *
 * <h2>Why this is a Core concern and not a moderation plugin's</h2>
 * Because vanish is not one feature, it is a promise every other feature has to keep. A staff member
 * who is hidden but still shows in the tablist, or still counts in "3 players online", or whose join
 * message went out anyway, is not hidden — and each of those is owned by a different subsystem. Only
 * the thing that owns the tablist, the chat and the player list can make the promise hold, which is
 * this library.
 *
 * <p>Every plugin then asks the same question — {@code isVanished} — instead of nine plugins each
 * keeping a set and five of them forgetting to check it.
 *
 * <h2>Why the effects are separate from the hiding</h2>
 * Because they come apart in practice. Somebody may want to be invisible without flying, or to look
 * at a build in creative without being hidden. Bundling them means the one you did not want comes
 * along, and turning it off afterwards is what leaves a moderator stuck in survival at bedrock.
 */
@DisplayName("vanish")
class VanishTest {

    /** What would have been done to the server, instead of a server. */
    private record Did(String what, UUID who) {
    }

    private final List<Did> did = new ArrayList<>();
    private final List<String> announced = new ArrayList<>();

    /** The pretend leave and join lines — see the nested "looking like a real departure" tests. */
    private final List<String> faked = new ArrayList<>();

    /** Whether the pretend sink lends flight through Core's leases. */
    private boolean leasing;

    private Vanish vanish() {
        return new Vanish(new VanishSink() {
            @Override
            public boolean leasesFlight() {
                return leasing;
            }

            @Override
            public void hide(UUID who, java.util.Set<UUID> mayStillSee) {
                did.add(new Did("hide", who));
            }

            @Override
            public void show(UUID who) {
                did.add(new Did("show", who));
            }

            @Override
            public void seenBy(UUID viewer, UUID who, boolean sees) {
                did.add(new Did((sees ? "seen-by:" : "unseen-by:") + viewer, who));
            }

            @Override
            public void allowFlight(UUID who, boolean allowed) {
                did.add(new Did("flight:" + allowed, who));
            }

            @Override
            public void collidable(UUID who, boolean collides) {
                did.add(new Did("collide:" + collides, who));
            }

            @Override
            public void silent(UUID who, boolean silent) {
                did.add(new Did("silent:" + silent, who));
            }

            @Override
            public void silentJoinLeave(UUID who, boolean silent) {
                announced.add((silent ? "quiet:" : "loud:") + who);
            }

            @Override
            public void announceDeparture(UUID who, java.util.Set<UUID> exceptThem) {
                faked.add("left:" + who + " except " + exceptThem.size());
            }

            @Override
            public void announceArrival(UUID who, java.util.Set<UUID> exceptThem) {
                faked.add("joined:" + who + " except " + exceptThem.size());
            }
        });
    }

    private static final UUID MOD = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @Nested
    @DisplayName("looking like a real departure")
    class LookingLikeALeave {

        @Test
        @DisplayName("vanishing announces a leave, and coming back announces a join")
        void bothHalves() {
            // The point of hiding is not being noticed, and a player who simply stops existing
            // mid-conversation is more conspicuous than one who left. Both halves or neither: a
            // moderator who "left" and then reappears without ever "joining" is one everybody works
            // out was hiding.
            Vanish vanish = vanish();

            vanish.vanish(MOD);
            assertThat(faked).containsExactly("left:" + MOD + " except 0");

            vanish.reveal(MOD);
            assertThat(faked).containsExactly("left:" + MOD + " except 0",
                    "joined:" + MOD + " except 0");
        }

        @Test
        @DisplayName("whoever may see them is not told they left")
        void staffAreNotFooled() {
            // Telling the staff that a moderator left while they can still see them standing there is
            // worse than saying nothing at all.
            Vanish vanish = vanish();
            vanish.maySeeVanished(OTHER, true);

            vanish.vanish(MOD);

            assertThat(faked).containsExactly("left:" + MOD + " except 1");
        }

        @Test
        @DisplayName("a server that would rather say nothing can switch it off")
        void itCanBeSwitchedOff() {
            Vanish vanish = vanish();
            vanish.fakeDeparture(false);

            vanish.vanish(MOD);
            vanish.reveal(MOD);

            assertThat(faked).isEmpty();
            assertThat(vanish.isFakingDeparture()).isFalse();
        }

        @Test
        @DisplayName("it is on by default, because being noticed is the thing vanish avoids")
        void onByDefault() {
            assertThat(vanish().isFakingDeparture()).isTrue();
        }

        @Test
        @DisplayName("a per-call override says nothing, even though the server-wide default would")
        void aSilentVanishOverridesTheDefault() {
            // For somebody who is not leaving in any sense a departure message would be honest about
            // — an eliminated tribute who stays connected and stays a spectator on the same server.
            Vanish vanish = vanish();

            vanish.vanish(MOD, false, false);
            vanish.reveal(MOD);

            assertThat(faked).isEmpty();
        }

        @Test
        @DisplayName("a silent vanish still reveals silently, whatever the flag says by then")
        void theRevealMatchesWhatActuallyHappened() {
            // The flag is asked at vanish time and remembered, not re-read at reveal time — a caller
            // who vanished somebody silently must get a silent reveal back even if the server-wide
            // default changed in between.
            Vanish vanish = vanish();

            vanish.vanish(MOD, false, false);
            vanish.fakeDeparture(true);
            vanish.reveal(MOD);

            assertThat(faked).isEmpty();
        }

        @Test
        @DisplayName("vanishing twice announces one departure")
        void notTwice() {
            // Re-hiding somebody already hidden does nothing at all, and a second leave message would
            // be the loudest possible way to announce that somebody is using a vanish command.
            Vanish vanish = vanish();

            vanish.vanish(MOD);
            vanish.vanish(MOD);

            assertThat(faked).hasSize(1);
        }
    }

    // ------------------------------------------------------------------ going and coming back

    @Nested
    @DisplayName("going invisible")
    class GoingAway {

        @Test
        @DisplayName("somebody can vanish")
        void vanishes() {
            Vanish vanish = vanish();
            assertThat(vanish.vanish(MOD)).isTrue();

            assertThat(vanish.isVanished(MOD)).isTrue();
            assertThat(did).extracting(Did::what).contains("hide");
        }

        @Test
        @DisplayName("vanishing twice changes nothing and is not an error")
        void isIdempotent() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);
            did.clear();

            assertThat(vanish.vanish(MOD)).isFalse();
            assertThat(did)
                    .as("re-hiding somebody already hidden re-sends packets to every player on "
                            + "the server for nothing")
                    .isEmpty();
        }

        @Test
        @DisplayName("coming back undoes it")
        void comesBack() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);
            assertThat(vanish.reveal(MOD)).isTrue();

            assertThat(vanish.isVanished(MOD)).isFalse();
            assertThat(did).extracting(Did::what).contains("show");
        }

        @Test
        @DisplayName("revealing somebody who was never hidden is not an error")
        void revealingNobody() {
            assertThat(vanish().reveal(MOD)).isFalse();
        }

        @Test
        @DisplayName("it can be toggled")
        void toggles() {
            Vanish vanish = vanish();
            assertThat(vanish.toggle(MOD)).isTrue();
            assertThat(vanish.toggle(MOD)).isFalse();
            assertThat(vanish.isVanished(MOD)).isFalse();
        }

        @Test
        @DisplayName("everybody hidden can be listed, which is the question every plugin asks")
        void listsThem() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);
            assertThat(vanish.everybodyVanished()).containsExactly(MOD);
            assertThat(vanish.isVanished(OTHER)).isFalse();
        }
    }

    // ------------------------------------------------------------------ the promise

    /**
     * The half that makes vanish mean something. Somebody hidden who still shows in the tablist, or
     * still counts in "3 players online", is not hidden.
     */
    @Nested
    @DisplayName("being properly gone")
    class BeingGone {

        @Test
        @DisplayName("they do not appear in a list of who is online")
        void notInTheList() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);

            assertThat(vanish.visibleOf(List.of(MOD, OTHER)))
                    .as("this is the call every plugin has to make instead of getOnlinePlayers")
                    .containsExactly(OTHER);
        }

        @Test
        @DisplayName("they do not count towards how many are online")
        void notInTheCount() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);
            assertThat(vanish.countOf(List.of(MOD, OTHER))).isEqualTo(1);
        }

        @Test
        @DisplayName("somebody who may see them still sees them")
        void staffSeeEachOther() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);

            assertThat(vanish.canSee(OTHER, MOD)).isFalse();
            assertThat(vanish.canSee(MOD, MOD))
                    .as("a moderator who cannot see themselves has been made to disappear rather "
                            + "than hidden")
                    .isTrue();

            vanish.maySeeVanished(OTHER, true);
            assertThat(vanish.canSee(OTHER, MOD))
                    .as("other staff have to be able to see each other or they walk into one "
                            + "another all night")
                    .isTrue();
        }

        @Test
        @DisplayName("their joining and leaving is quiet")
        void noJoinMessage() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);
            assertThat(announced).contains("quiet:" + MOD);

            vanish.reveal(MOD);
            assertThat(announced).contains("loud:" + MOD);
        }

        @Test
        @DisplayName("they do not bump into anybody")
        void noCollisions() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);
            assertThat(did).extracting(Did::what)
                    .as("an invisible wall that shoves players around is worse than being seen")
                    .contains("collide:false");
        }

        @Test
        @DisplayName("they make no sound, and coming back gives it back")
        void noSound() {
            // Footsteps stopping right beside somebody, with nobody visibly there, say exactly as
            // much as seeing the moderator outright — being invisible but audible is not hidden.
            Vanish vanish = vanish();

            vanish.vanish(MOD);
            assertThat(did).extracting(Did::what).contains("silent:true");

            did.clear();
            vanish.reveal(MOD);
            assertThat(did).extracting(Did::what)
                    .as("a moderator who stays silent forever is the next bug report")
                    .contains("silent:false");
        }
    }

    // ------------------------------------------------------------------ the extras

    @Nested
    @DisplayName("what comes with it")
    class Extras {

        @Test
        @DisplayName("flight is offered but not forced")
        void flightIsAChoice() {
            Vanish vanish = vanish();
            vanish.flightWhileVanished(false);
            vanish.vanish(MOD);

            assertThat(did).extracting(Did::what).doesNotContain("flight:true");
        }

        @Test
        @DisplayName("with it on, flight comes and goes with the vanish")
        void flightFollows() {
            Vanish vanish = vanish();
            vanish.flightWhileVanished(true);
            vanish.vanish(MOD);
            assertThat(did).extracting(Did::what).contains("flight:true");

            did.clear();
            vanish.reveal(MOD);
            assertThat(did).extracting(Did::what)
                    .as("leaving somebody flying after they come back is how a moderator ends up "
                            + "explaining why they can fly")
                    .contains("flight:false");
        }

        @Test
        @DisplayName("somebody who could already fly keeps flying afterwards")
        void doesNotTakeAwayWhatWasAlreadyThere() {
            Vanish vanish = vanish();
            vanish.flightWhileVanished(true);
            vanish.vanish(MOD, true);

            did.clear();
            vanish.reveal(MOD);
            assertThat(did).extracting(Did::what)
                    .as("a creative-mode builder who vanished must not land in the void when "
                            + "they come back")
                    .doesNotContain("flight:false");
        }

        @Test
        @DisplayName("through leases, its own lease is taken even when they could already fly — a claim's flight may end first")
        void leasedFlightIsTakenAnyway() {
            leasing = true;
            Vanish vanish = vanish();
            vanish.flightWhileVanished(true);
            vanish.vanish(MOD, true);
            assertThat(did).extracting(Did::what).contains("flight:true");

            did.clear();
            vanish.reveal(MOD);
            assertThat(did).extracting(Did::what).contains("flight:false");
        }
    }

    // ------------------------------------------------------------------ not losing track

    @Nested
    @DisplayName("not losing track of anybody")
    class Bookkeeping {

        @Test
        @DisplayName("somebody who leaves while hidden is still hidden when they return")
        void survivesALeave() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);
            vanish.forgetSession(MOD);

            assertThat(vanish.isVanished(MOD))
                    .as("a moderator who reconnects and is suddenly visible has been given away "
                            + "by the plugin that was hiding them")
                    .isTrue();
        }

        @Test
        @DisplayName("everything can be undone at once, for a shutdown")
        void revealsEverybody() {
            Vanish vanish = vanish();
            vanish.vanish(MOD);
            vanish.vanish(OTHER);

            assertThat(vanish.revealEverybody()).isEqualTo(2);
            assertThat(vanish.everybodyVanished()).isEmpty();
        }

        @Test
        @DisplayName("who may see hidden players is remembered per person")
        void seeingIsPerPerson() {
            Vanish vanish = vanish();
            vanish.maySeeVanished(MOD, true);
            assertThat(vanish.maySeeVanished(MOD)).isTrue();
            assertThat(vanish.maySeeVanished(OTHER)).isFalse();

            vanish.maySeeVanished(MOD, false);
            assertThat(vanish.maySeeVanished(MOD)).isFalse();
        }
    }

    @Test
    @DisplayName("being given or losing the right to see the hidden takes effect at once, not at the next join")
    void seeingIsAppliedWhenItChanges() {
        Vanish vanish = vanish();
        UUID hidden = UUID.randomUUID();
        UUID moderator = UUID.randomUUID();
        vanish.vanish(hidden);
        did.clear();

        vanish.maySeeVanished(moderator, true);
        vanish.maySeeVanished(moderator, true);
        vanish.maySeeVanished(moderator, false);

        assertThat(did).containsExactly(
                new Did("seen-by:" + moderator, hidden),
                new Did("unseen-by:" + moderator, hidden));
    }
}
