package de.raindancer.core.ui.effect;

import org.bukkit.Particle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Core's own particles besides the coffee beans: the Twitch glitch trail and Prime's stardust. */
class OwnShowsTest {

    private static final Set<Integer> TWITCH_PURPLES = Set.of(0x9146FF, 0xBF94FF, 0x772CE8);
    private static final Set<Integer> GLITCH_TINTS = Set.of(0x00E5FF, 0xFF3C78);

    @Test
    @DisplayName("TWITCH and STARDUST are own particles: shown without a colour, found in any case and with spaces")
    void ownParticles() {
        for (String own : new String[]{"TWITCH", "twitch", " Stardust ", "COFFEE"}) {
            assertThat(ParticleShows.isOwn(own)).as(own).isTrue();
            assertThat(ParticleShows.canShow(own)).as(own).isTrue();
            assertThat(ParticleShows.takesColour(own)).as(own).isFalse();
        }
        assertThat(ParticleShows.isOwn("FLAME")).isFalse();
        assertThat(ParticleShows.isOwn(null)).isFalse();
        assertThat(ParticleShows.owns()).containsExactlyInAnyOrder("COFFEE", "TWITCH", "STARDUST");
    }

    @Test
    @DisplayName("the Twitch trail is drawn as dust pixels, the stardust as end-rod sparks and dust; the beans stay items")
    void drawnAs() {
        assertThat(ParticleShows.drawnAs("TWITCH", null)).isEqualTo(Particle.DUST);
        assertThat(ParticleShows.drawnAs("TWITCH", 3)).as("a pixel is never turned into a trail").isEqualTo(Particle.DUST);
        assertThat(ParticleShows.drawnAs("STARDUST", null)).isEqualTo(Particle.END_ROD);
        assertThat(ParticleShows.drawnAs("COFFEE", null)).isEqualTo(Particle.ITEM);
    }

    @Test
    @DisplayName("Twitch pixels are Twitch purple; about every fifth point glitches sideways in cyan or magenta")
    void twitchPixels() {
        long glitches = 0;
        for (int index = 0; index < 100; index++) {
            OwnShows.Point point = OwnShows.point("TWITCH", index, 7);
            assertThat(point.particle()).isEqualTo(Particle.DUST);
            if (OwnShows.isGlitch(index, 7)) {
                glitches++;
                assertThat(point.rgb()).isIn(GLITCH_TINTS);
                assertThat(Math.abs(point.dx()) + Math.abs(point.dz())).isGreaterThan(0.1).isLessThan(0.4);
            } else {
                assertThat(point.rgb()).isIn(TWITCH_PURPLES);
                assertThat(point.dx()).isZero();
                assertThat(point.dz()).isZero();
            }
        }
        assertThat(glitches).isEqualTo(20);
        assertThat(OwnShows.TWITCH_PURPLE).isEqualTo(0x9146FF);
    }

    @Test
    @DisplayName("the glitch moves with the tick, so a point never glitches twice in a row")
    void glitchFlickers() {
        for (int index = 0; index < 50; index++) {
            assertThat(OwnShows.isGlitch(index, 10) && OwnShows.isGlitch(index, 11)).as("point " + index).isFalse();
        }
        assertThat(IntStream.range(0, 50).filter(index -> OwnShows.isGlitch(index, 10)).boxed().toList())
                .isNotEqualTo(IntStream.range(0, 50).filter(index -> OwnShows.isGlitch(index, 11)).boxed().toList());
    }

    @Test
    @DisplayName("stardust: a third of the points are end-rod sparks, the rest gold dust; nothing leaves the shape")
    void stardust() {
        long sparks = IntStream.range(0, 30)
                .filter(index -> OwnShows.point("STARDUST", index, 0).particle() == Particle.END_ROD).count();
        assertThat(sparks).isEqualTo(10);
        for (int index = 0; index < 30; index++) {
            OwnShows.Point point = OwnShows.point("STARDUST", index, 4);
            assertThat(point.dx()).isZero();
            assertThat(point.dz()).isZero();
            if (point.particle() == Particle.DUST) {
                assertThat(point.rgb()).isIn(Set.of(0xFFD966, 0xFFF4C2));
            }
        }
    }

    @Test
    @DisplayName("a point is only asked of a particle that has them")
    void onlyFancyShows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OwnShows.point("COFFEE", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
