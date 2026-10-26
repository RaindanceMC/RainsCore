package de.raindancer.core.ui.effect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which particles can be worn at all, and which of them take a colour. */
class ParticleShowsTest {

    @Test
    @DisplayName("a particle that needs nothing can be shown, and takes no colour")
    void plainParticles() {
        assertThat(ParticleShows.canShow("flame")).isTrue();
        assertThat(ParticleShows.takesColour("FLAME")).isFalse();
    }

    @Test
    @DisplayName("dust needs a colour, and with one it can be shown")
    void dustTakesAColour() {
        assertThat(ParticleShows.canShow("DUST")).isTrue();
        assertThat(ParticleShows.takesColour("DUST")).isTrue();
    }

    @Test
    @DisplayName("a particle that needs a block or an item cannot be worn — it would show nothing")
    void blockParticlesCannot() {
        assertThat(ParticleShows.canShow("BLOCK")).isFalse();
        assertThat(ParticleShows.canShow("ITEM")).isFalse();
    }

    @Test
    @DisplayName("a name that is no particle is not shown, rather than thrown about")
    void unknown() {
        assertThat(ParticleShows.canShow("sparkles")).isFalse();
        assertThat(ParticleShows.canShow(null)).isFalse();
        assertThat(ParticleShows.takesColour("sparkles")).isFalse();
    }

    @Test
    @DisplayName("a gradient runs from the first colour to the second along the shape; without a second it is one colour")
    void gradient() {
        assertThat(ParticleShows.colourAlong(0xFF0000, 0x0000FF, 0)).isEqualTo(0xFF0000);
        assertThat(ParticleShows.colourAlong(0xFF0000, 0x0000FF, 1)).isEqualTo(0x0000FF);
        assertThat(ParticleShows.colourAlong(0xFF0000, 0x0000FF, 0.5)).isEqualTo(0x800080);
        assertThat(ParticleShows.colourAlong(0x00FF00, null, 0.7)).isEqualTo(0x00FF00);
    }

    @Test
    @DisplayName("denser is finer: the dust shrinks as the points come closer, so a dense shape is sharp, not a cloud")
    void dustSize() {
        assertThat(ParticleShows.dustSize(1)).isEqualTo(1.0f);
        assertThat(ParticleShows.dustSize(4)).isLessThan(ParticleShows.dustSize(2));
        assertThat(ParticleShows.dustSize(16)).isLessThan(ParticleShows.dustSize(4)).isGreaterThanOrEqualTo(0.35f);
        org.bukkit.Particle.DustOptions dust = (org.bukkit.Particle.DustOptions)
                BukkitEffectSink.dataFor(org.bukkit.Particle.DustOptions.class, 0xff0000, 0.5f);
        assertThat(dust.getSize()).isEqualTo(0.5f);
    }

    @Test
    @DisplayName("a coloured particle given a lifetime is drawn as a trail point, gone after exactly that many ticks")
    void shortLived() {
        assertThat(ParticleShows.drawnAs("DUST", 2)).isEqualTo(org.bukkit.Particle.TRAIL);
        assertThat(ParticleShows.drawnAs("DUST", null)).isEqualTo(org.bukkit.Particle.DUST);
        // A flame cannot be told how long to live; it stays a flame.
        assertThat(ParticleShows.drawnAs("FLAME", 2)).isEqualTo(org.bukkit.Particle.FLAME);
        org.bukkit.Location at = new org.bukkit.Location(null, 1, 2, 3);
        org.bukkit.Particle.Trail trail = (org.bukkit.Particle.Trail) ParticleShows.trailData(at, 0x112233, 3);
        assertThat(trail.getDuration()).isEqualTo(3);
        assertThat(trail.getColor().asRGB()).isEqualTo(0x112233);
        assertThat(trail.getTarget()).isEqualTo(at);
    }

    @Test
    @DisplayName("a worn shape faces the way the body does, not the way the head looks")
    void facesTheBody() {
        org.bukkit.entity.Player wearer = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        org.mockito.Mockito.when(wearer.getBodyYaw()).thenReturn(30f);
        org.mockito.Mockito.when(wearer.getLocation()).thenReturn(new org.bukkit.Location(null, 0, 0, 0, 120f, 0));

        assertThat(ParticleShows.facing(wearer)).isEqualTo(30f);
    }

    @Test
    @DisplayName("drawn naturally, a shape shows a rotating share of its points each time, and every point gets its turn")
    void share() {
        int share = 3;
        for (int index = 0; index < 10; index++) {
            int turns = 0;
            for (long tick = 0; tick < share; tick++) {
                if (ParticleShows.isDrawnNow(index, tick, share)) {
                    turns++;
                }
            }
            assertThat(turns).as("point %d", index).isEqualTo(1);
        }
        assertThat(ParticleShows.isDrawnNow(7, 5, 1)).as("a share of one is every point").isTrue();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("COFFEE is Core's own particle: shown without a colour, drawn as tumbling beans with a curl of steam")
    void coffee() {
        org.assertj.core.api.Assertions.assertThat(ParticleShows.canShow("COFFEE")).isTrue();
        org.assertj.core.api.Assertions.assertThat(ParticleShows.canShow("coffee")).isTrue();
        org.assertj.core.api.Assertions.assertThat(ParticleShows.takesColour("COFFEE")).isFalse();
        org.assertj.core.api.Assertions.assertThat(ParticleShows.drawnAs("COFFEE", null))
                .isEqualTo(org.bukkit.Particle.ITEM);
        org.assertj.core.api.Assertions.assertThat(ParticleShows.drawnAs("COFFEE", 5))
                .as("a lifetime is for coloured particles only").isEqualTo(org.bukkit.Particle.ITEM);
        long steam = java.util.stream.IntStream.range(0, 12).filter(ParticleShows::isSteam).count();
        org.assertj.core.api.Assertions.assertThat(steam).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(ParticleShows.isOwn("COFFEE")).isTrue();
        org.assertj.core.api.Assertions.assertThat(ParticleShows.isOwn("FLAME")).isFalse();
    }
}
