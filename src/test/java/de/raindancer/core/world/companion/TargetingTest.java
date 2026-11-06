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
}
