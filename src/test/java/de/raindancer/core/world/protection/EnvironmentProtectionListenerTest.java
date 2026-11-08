package de.raindancer.core.world.protection;

import org.bukkit.entity.Enderman;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.Turtle;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A turtle laying an egg fires {@link org.bukkit.event.entity.EntityChangeBlockEvent} exactly like a zombie
 * breaking a door, so {@code griefFlagFor} was lumping them together under {@link LandFlag#MOB_GRIEF} — a
 * claim owner who (sensibly) leaves mob grief off can never breed turtles on their own claim: the turtle digs
 * the nest animation, then the egg placement is silently cancelled.
 */
class EnvironmentProtectionListenerTest {

    @Test
    @DisplayName("a turtle laying its egg is not mob grief")
    void turtleEggIsNotGrief() {
        assertThat(EnvironmentProtectionListener.griefFlagFor(mock(Turtle.class))).isNull();
    }

    @Test
    @DisplayName("a zombie breaking a door is still mob grief")
    void zombieIsStillGrief() {
        assertThat(EnvironmentProtectionListener.griefFlagFor(mock(Zombie.class))).isEqualTo(LandFlag.MOB_GRIEF);
    }

    @Test
    @DisplayName("an enderman is still judged under its own flag")
    void endermanIsStillItsOwnFlag() {
        assertThat(EnvironmentProtectionListener.griefFlagFor(mock(Enderman.class)))
                .isEqualTo(LandFlag.ENDERMAN_GRIEF);
    }

    @Test
    @DisplayName("players and falling blocks stay exempt")
    void playersAndFallingBlocksStayExempt() {
        assertThat(EnvironmentProtectionListener.griefFlagFor(mock(Player.class))).isNull();
        assertThat(EnvironmentProtectionListener.griefFlagFor(mock(FallingBlock.class))).isNull();
    }

    @Test
    @DisplayName("TNT lit by a player who is online counts as theirs (a raid's charge); anything else as nobody's")
    void tntIsWhoeverLitIt() {
        Player raider = mock(Player.class);
        org.mockito.Mockito.when(raider.isOnline()).thenReturn(true);
        org.bukkit.entity.TNTPrimed tnt = mock(org.bukkit.entity.TNTPrimed.class);
        org.mockito.Mockito.when(tnt.getSource()).thenReturn(raider);
        assertThat(EnvironmentProtectionListener.litBy(tnt)).isSameAs(raider);

        Player gone = mock(Player.class);
        org.bukkit.entity.TNTPrimed left = mock(org.bukkit.entity.TNTPrimed.class);
        org.mockito.Mockito.when(left.getSource()).thenReturn(gone);
        assertThat(EnvironmentProtectionListener.litBy(left)).isNull();
        assertThat(EnvironmentProtectionListener.litBy(mock(org.bukkit.entity.Creeper.class))).isNull();
    }
}
