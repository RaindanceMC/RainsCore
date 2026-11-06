package de.raindancer.core.world.companion;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("what a pet goes for")
class TargetingTest {

    private final Targeting rule = new Targeting();

    private static Targeting.Candidate candidate(Targeting.Kind kind, boolean hurtOwner, boolean ownerHit) {
        return new Targeting.Candidate(kind, hurtOwner, ownerHit, false);
    }

    @Test
    @DisplayName("a passive pet goes for nothing, not even what hurt its owner")
    void passive() {
        assertThat(rule.attacks(Stance.PASSIVE, candidate(Targeting.Kind.MONSTER, true, true), true)).isFalse();
    }

    @Test
    @DisplayName("a defending pet goes for what hurt its owner, and only that")
    void defensive() {
        assertThat(rule.attacks(Stance.DEFENSIVE, candidate(Targeting.Kind.MONSTER, true, false), true)).isTrue();
        assertThat(rule.attacks(Stance.DEFENSIVE, candidate(Targeting.Kind.MONSTER, false, true), true)).isFalse();
        assertThat(rule.attacks(Stance.DEFENSIVE, candidate(Targeting.Kind.MONSTER, false, false), true)).isFalse();
    }

    @Test
    @DisplayName("an aggressive pet also joins its owner's fights and goes for monsters nearby")
    void aggressive() {
        assertThat(rule.attacks(Stance.AGGRESSIVE, candidate(Targeting.Kind.ANIMAL, false, true), true)).isTrue();
        assertThat(rule.attacks(Stance.AGGRESSIVE, candidate(Targeting.Kind.MONSTER, false, false), true)).isTrue();
        assertThat(rule.attacks(Stance.AGGRESSIVE, candidate(Targeting.Kind.ANIMAL, false, false), true)).isFalse();
        assertThat(rule.attacks(Stance.AGGRESSIVE, candidate(Targeting.Kind.PLAYER, false, false), true)).isFalse();
    }

    @Test
    @DisplayName("players only where players may fight, and only when it is their fight")
    void players() {
        Targeting.Candidate attacker = candidate(Targeting.Kind.PLAYER, true, false);
        assertThat(rule.attacks(Stance.DEFENSIVE, attacker, true)).isTrue();
        assertThat(rule.attacks(Stance.DEFENSIVE, attacker, false)).isFalse();
    }

    @Test
    @DisplayName("never its owner, its owner's own pets, or something that cannot be fought")
    void never() {
        for (Targeting.Kind kind : new Targeting.Kind[]{Targeting.Kind.OWNER, Targeting.Kind.OWN_PET,
                Targeting.Kind.UNFIGHTABLE}) {
            assertThat(rule.attacks(Stance.AGGRESSIVE, candidate(kind, true, true), true)).as(kind.name()).isFalse();
        }
    }

    @Test
    @DisplayName("a villager or another player's pet only when its owner started it")
    void protectedOnes() {
        Targeting.Candidate villager = new Targeting.Candidate(Targeting.Kind.ANIMAL, false, false, true);
        assertThat(rule.attacks(Stance.AGGRESSIVE, villager, true)).isFalse();
        Targeting.Candidate struck = new Targeting.Candidate(Targeting.Kind.ANIMAL, false, true, true);
        assertThat(rule.attacks(Stance.AGGRESSIVE, struck, true)).isTrue();
    }

    private static Targeting.Seen seen(String name, int rank, double health, double distance) {
        return new Targeting.Seen(java.util.UUID.nameUUIDFromBytes(name.getBytes()), rank == 3, rank == 2, rank == 1,
                health, distance, false);
    }

    @Test
    @DisplayName("whatever hurts its owner comes first, then what goes for them, then what they hit")
    void priority() {
        Targeting.Seen hurter = seen("hurter", 3, 1, 12);
        Targeting.Seen threat = seen("threat", 2, 1, 6);
        Targeting.Seen struck = seen("struck", 1, 1, 3);
        Targeting.Seen near = seen("near", 0, 0.1, 1);
        assertThat(Targeting.pick(java.util.List.of(near, struck, threat, hurter), null)).contains(hurter);
        assertThat(Targeting.pick(java.util.List.of(near, struck, threat), null)).contains(threat);
        assertThat(Targeting.pick(java.util.List.of(near, struck), null)).contains(struck);
    }

    @Test
    @DisplayName("among the rest, the weakest, then the closest")
    void weakestThenClosest() {
        Targeting.Seen weak = seen("weak", 0, 0.2, 9);
        Targeting.Seen strong = seen("strong", 0, 1, 2);
        Targeting.Seen strongNearer = seen("strongNearer", 0, 0.95, 1);
        assertThat(Targeting.pick(java.util.List.of(strong, weak), null)).contains(weak);
        assertThat(Targeting.pick(java.util.List.of(strong, strongNearer), null)).contains(strongNearer);
        assertThat(Targeting.pick(java.util.List.of(), null)).isEmpty();
    }

    @Test
    @DisplayName("it sticks with its foe unless something more pressing turns up")
    void sticky() {
        Targeting.Seen current = seen("current", 0, 1, 8);
        Targeting.Seen weaker = seen("weaker", 0, 0.3, 2);
        assertThat(Targeting.pick(java.util.List.of(weaker, current), current.id())).contains(current);
        Targeting.Seen hurter = seen("hurter", 3, 1, 12);
        assertThat(Targeting.pick(java.util.List.of(weaker, current, hurter), current.id())).contains(hurter);
    }

    @Test
    @DisplayName("a foe its owner's other companions will finish anyway is left to them while there is another")
    void noOverkill() {
        java.util.UUID weakling = java.util.UUID.randomUUID();
        Targeting.Seen covered = new Targeting.Seen(weakling, false, false, false, 0.2, 2, true);
        Targeting.Seen other = seen("other", 0, 1, 8);
        assertThat(Targeting.pick(java.util.List.of(covered, other), null)).contains(other);
        assertThat(Targeting.pick(java.util.List.of(covered), null)).contains(covered);
        Targeting.Seen urgent = new Targeting.Seen(weakling, true, false, false, 0.2, 2, true);
        assertThat(Targeting.pick(java.util.List.of(urgent, other), null)).contains(other);
    }

    @Test
    @DisplayName("enough companions on one foe: the rest are counted, the first ones keep it")
    void claims() {
        assertThat(Targeting.covered(4, java.util.List.of(3.0, 2.0))).isTrue();
        assertThat(Targeting.covered(4, java.util.List.of(3.0))).isFalse();
        assertThat(Targeting.covered(4, java.util.List.of())).isFalse();
    }
}
