package de.raindancer.core.world.companion;

import org.bukkit.entity.EntityType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("what a ridden companion does when its rider swings")
class AbilitiesTest {

    @Nested
    @DisplayName("a companion's ability on its spec")
    class OnTheSpec {

        @Test
        @DisplayName("none unless given; power and haste start at one")
        void defaults() {
            CompanionSpec plain = CompanionSpec.of(EntityType.WOLF);
            assertThat(plain.ability()).isNull();
            assertThat(plain.abilityPower()).isEqualTo(1);
            assertThat(plain.abilityHaste()).isEqualTo(1);

            CompanionSpec wither = CompanionSpec.of(EntityType.WITHER).ability(CompanionAbility.WITHER_SKULLS)
                    .abilityPower(1.5).abilityHaste(1.25);
            assertThat(wither.ability()).isEqualTo(CompanionAbility.WITHER_SKULLS);
            assertThat(wither.abilityPower()).isEqualTo(1.5);
            assertThat(wither.abilityHaste()).isEqualTo(1.25);
            assertThat(wither.silent(true).ability()).as("kept by every with").isEqualTo(CompanionAbility.WITHER_SKULLS);
        }

        @Test
        @DisplayName("power and haste stay sensible whatever a plugin hands in")
        void clamped() {
            CompanionSpec odd = CompanionSpec.of(EntityType.WITHER).abilityPower(-3).abilityHaste(0);
            assertThat(odd.abilityPower()).isEqualTo(0.1);
            assertThat(odd.abilityHaste()).isEqualTo(0.25);
            assertThat(CompanionSpec.of(EntityType.WITHER).abilityHaste(99).abilityHaste()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("waiting between two")
    class Cooldown {

        @Test
        @DisplayName("each ability has its own wait, and haste shortens it")
        void haste() {
            for (CompanionAbility ability : CompanionAbility.values()) {
                assertThat(ability.cooldownSeconds()).as(ability.name()).isBetween(4, 30);
                assertThat(ability.title()).as(ability.name()).isNotBlank();
            }
            int plain = Abilities.cooldownTicks(CompanionAbility.SONIC_BOOM, 1);
            assertThat(plain).isEqualTo(CompanionAbility.SONIC_BOOM.cooldownSeconds() * 20);
            assertThat(Abilities.cooldownTicks(CompanionAbility.SONIC_BOOM, 2)).isEqualTo(plain / 2);
            assertThat(Abilities.cooldownTicks(CompanionAbility.WITHER_SKULLS, 100))
                    .as("never quicker than a second").isGreaterThanOrEqualTo(20);
        }

        @Test
        @DisplayName("the seconds left, rounded up, for the rider's action bar")
        void left() {
            assertThat(Abilities.secondsLeft(100, 140)).isEqualTo(2);
            assertThat(Abilities.secondsLeft(100, 101)).isEqualTo(1);
            assertThat(Abilities.secondsLeft(140, 100)).isZero();
        }
    }

    @Nested
    @DisplayName("who an ability may touch")
    class Fair {

        private Abilities.Victim victim(boolean player, boolean enemy, boolean huntsOwner, boolean pvp) {
            return new Abilities.Victim(false, false, player, false, enemy, huntsOwner, pvp);
        }

        @Test
        @DisplayName("never the rider, nor their own companions")
        void notOwnSide() {
            assertThat(Abilities.hits(new Abilities.Victim(true, false, true, false, false, false, true))).isFalse();
            assertThat(Abilities.hits(new Abilities.Victim(false, true, false, false, true, false, true))).isFalse();
        }

        @Test
        @DisplayName("monsters and whatever hunts the rider, but no cow, villager or horse standing by")
        void mobs() {
            assertThat(Abilities.hits(victim(false, true, false, false))).isTrue();
            assertThat(Abilities.hits(victim(false, false, true, false))).isTrue();
            assertThat(Abilities.hits(victim(false, false, false, true))).isFalse();
        }

        @Test
        @DisplayName("players, and other people's companions, only where Combat lets the rider fight them")
        void players() {
            assertThat(Abilities.hits(victim(true, false, false, true))).isTrue();
            assertThat(Abilities.hits(victim(true, false, false, false))).isFalse();
            assertThat(Abilities.hits(new Abilities.Victim(false, false, false, true, false, false, true))).isTrue();
            assertThat(Abilities.hits(new Abilities.Victim(false, false, false, true, true, false, false))).isFalse();
        }
    }

    @Test
    @DisplayName("a beam hits what is near its line, not what is behind its start or past its end")
    void beam() {
        Vector start = new Vector(0, 0, 0);
        Vector along = new Vector(1, 0, 0);
        assertThat(Abilities.distanceToBeam(start, along, 20, new Vector(10, 1, 0))).isCloseTo(1, within(1e-9));
        assertThat(Abilities.distanceToBeam(start, along, 20, new Vector(-3, 0, 0))).isCloseTo(3, within(1e-9));
        assertThat(Abilities.distanceToBeam(start, along, 20, new Vector(25, 0, 0))).isCloseTo(5, within(1e-9));
    }

    @Test
    @DisplayName("a cone in front: inside its angle and reach, nothing behind")
    void cone() {
        Vector at = new Vector(0, 0, 0);
        Vector facing = new Vector(0, 0, 1);
        assertThat(Abilities.inCone(at, facing, 8, 50, new Vector(1, 0, 5))).isTrue();
        assertThat(Abilities.inCone(at, facing, 8, 50, new Vector(0, 0, -2))).isFalse();
        assertThat(Abilities.inCone(at, facing, 8, 50, new Vector(6, 0, 1))).as("too far to the side").isFalse();
        assertThat(Abilities.inCone(at, facing, 8, 50, new Vector(0, 0, 9))).as("too far").isFalse();
    }
}
