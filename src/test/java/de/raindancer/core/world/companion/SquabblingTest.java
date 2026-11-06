package de.raindancer.core.world.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("grumpy companions squaring up to other people's — for show only")
class SquabblingTest {

    private static final UUID MINE = UUID.randomUUID();
    private static final Steering.Vec HERE = new Steering.Vec(0, 64, 0);

    private static Squabbling.Rival rival(UUID owner, double x) {
        return new Squabbling.Rival(UUID.randomUUID(), owner, new Steering.Vec(x, 64, 0));
    }

    @Test
    @DisplayName("never with its own owner's companions; the nearest of somebody else's within reach")
    void whom() {
        Squabbling.Rival own = rival(MINE, 1);
        Squabbling.Rival theirs = rival(UUID.randomUUID(), 3);
        Squabbling.Rival further = rival(UUID.randomUUID(), 5);
        Squabbling.Rival tooFar = rival(UUID.randomUUID(), 12);
        assertThat(Squabbling.rival(HERE, MINE, List.of(own, further, theirs, tooFar))).contains(theirs);
        assertThat(Squabbling.rival(HERE, MINE, List.of(own, tooFar))).isEmpty();
    }

    @Test
    @DisplayName("a good-natured one never starts; a grumpy one now and then")
    void temper() {
        Random random = new Random(2);
        int calm = 0;
        int grumpy = 0;
        for (int second = 0; second < 2000; second++) {
            calm += Squabbling.picksOn(0, random) ? 1 : 0;
            grumpy += Squabbling.picksOn(1, random) ? 1 : 0;
        }
        assertThat(calm).isZero();
        assertThat(grumpy).isBetween(20, 200);
        assertThat(Squabbling.lasts(random)).isBetween(40L, 120L);
    }

    @Test
    @DisplayName("it stops short of its rival — face to face, never touching")
    void faceOff() {
        Steering.Vec rival = new Steering.Vec(4, 64, 0);
        Steering.Vec spot = Squabbling.faceOff(rival, HERE, 0.6);
        assertThat(Math.hypot(spot.x() - rival.x(), spot.z() - rival.z()))
                .isEqualTo(0.6 + Squabbling.GAP, within(1e-9));
        assertThat(spot.x()).isLessThan(rival.x());
    }
}
