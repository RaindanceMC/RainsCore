package de.raindancer.core.world.companion;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("what a companion is made as")
class CompanionSpecTest {

    private static CompanionSpec tall(double height) {
        return CompanionSpec.of(EntityType.WOLF).height(height);
    }

    @Test
    @DisplayName("it stands its height in player heights, however tall its mob is")
    void height() {
        assertThat(tall(0.5).scaleFor(8.0) * 8.0).isEqualTo(0.9, within(1e-9));
        assertThat(tall(0.3).scaleFor(1.0)).isEqualTo(0.54, within(1e-9));
    }

    @Test
    @DisplayName("never as big as the wild mob")
    void smaller() {
        assertThat(tall(0.6).scaleFor(0.4)).isEqualTo(CompanionSpec.LARGEST_SCALE);
        assertThat(tall(0.6).scaleFor(0)).isEqualTo(CompanionSpec.LARGEST_SCALE);
    }

    @Test
    @DisplayName("numbers that make no sense are made sensible")
    void clamped() {
        CompanionSpec odd = CompanionSpec.of(EntityType.WOLF).health(-3).damage(-1).speed(9).fleesBelow(4);
        assertThat(odd.health()).isEqualTo(1);
        assertThat(odd.damage()).isZero();
        assertThat(odd.speed()).isEqualTo(CompanionSpec.FASTEST);
        assertThat(odd.fleesBelow()).isEqualTo(1);
    }

    @Test
    @DisplayName("each change is a new spec; the old one is untouched")
    void immutable() {
        CompanionSpec wolf = CompanionSpec.of(EntityType.WOLF);
        CompanionSpec fierce = wolf.damage(9).stance(Stance.AGGRESSIVE).fighting(Fighting.GUARD).flies(true)
                .baby(true).rideable(false).missile(EntityType.SNOWBALL);
        assertThat(wolf.damage()).isNotEqualTo(9);
        assertThat(fierce.stance()).isEqualTo(Stance.AGGRESSIVE);
        assertThat(fierce.fighting()).isEqualTo(Fighting.GUARD);
        assertThat(fierce.flies()).isTrue();
        assertThat(fierce.baby()).isTrue();
        assertThat(fierce.rideable()).isFalse();
        assertThat(fierce.missile()).isEqualTo(EntityType.SNOWBALL);
    }

    @Test
    @DisplayName("a particle is worn in a shape that fits a small body; wings are not")
    void particles() {
        CompanionSpec plain = CompanionSpec.of(EntityType.WOLF);
        assertThat(plain.particle()).isEmpty();
        CompanionSpec sparkly = plain.particle("FLAME", de.raindancer.core.ui.effect.ParticleShape.HALO);
        assertThat(sparkly.particle()).isEqualTo("FLAME");
        assertThat(sparkly.particleShape()).isEqualTo(de.raindancer.core.ui.effect.ParticleShape.HALO);
        assertThat(plain.particle("FLAME", de.raindancer.core.ui.effect.ParticleShape.DRAGON_WINGS).particleShape())
                .isEqualTo(de.raindancer.core.ui.effect.ParticleShape.AURA);
        assertThat(CompanionSpec.fitsSmallBody(de.raindancer.core.ui.effect.ParticleShape.TRAIL)).isTrue();
        assertThat(CompanionSpec.fitsSmallBody(de.raindancer.core.ui.effect.ParticleShape.WINGS)).isFalse();
    }

    @Test
    @DisplayName("a companion can be muted; it is loud by default")
    void silent() {
        assertThat(CompanionSpec.of(EntityType.WITHER).silent()).isFalse();
        assertThat(CompanionSpec.of(EntityType.WITHER).silent(true).silent()).isTrue();
    }
}
