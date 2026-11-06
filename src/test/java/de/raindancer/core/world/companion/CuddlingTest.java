package de.raindancer.core.world.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("companions cuddling up while their owner stands about")
class CuddlingTest {

    private static final Steering.Vec HERE = new Steering.Vec(0, 64, 0);

    private static Cuddling.Mate mate(double x, boolean resting, boolean free) {
        return new Cuddling.Mate(UUID.randomUUID(), new Steering.Vec(x, 64, 0), resting, free);
    }

    @Test
    @DisplayName("it goes to the nearest of its owner's resting companions that nobody cuddles yet")
    void partner() {
        Cuddling.Mate near = mate(2, true, true);
        Cuddling.Mate nearer = mate(1.5, false, true);
        Cuddling.Mate taken = mate(1, true, false);
        Cuddling.Mate far = mate(9, true, true);
        assertThat(Cuddling.partner(HERE, List.of(near, nearer, taken, far))).contains(near);
        assertThat(Cuddling.partner(HERE, List.of(far))).isEmpty();
        assertThat(Cuddling.partner(HERE, List.of())).isEmpty();
    }

    @Test
    @DisplayName("it lies right against its partner, on the side it comes from — touching, never on top")
    void beside() {
        Steering.Vec partner = new Steering.Vec(3, 64, 0);
        Steering.Vec spot = Cuddling.beside(partner, HERE, 0.6);
        assertThat(Math.hypot(spot.x() - partner.x(), spot.z() - partner.z())).isEqualTo(0.6, within(1e-9));
        assertThat(spot.x()).isLessThan(partner.x());
        Steering.Vec onTop = Cuddling.beside(partner, partner, 0.6);
        assertThat(Math.hypot(onTop.x() - partner.x(), onTop.z() - partner.z())).isEqualTo(0.6, within(1e-9));
    }

    @Test
    @DisplayName("now and then, not all the time, and for a while")
    void sometimes() {
        Random random = new Random(4);
        int started = 0;
        for (int second = 0; second < 1000; second++) {
            started += Cuddling.feelsLikeIt(random) ? 1 : 0;
        }
        assertThat(started).isBetween(10, 120);
        long lasts = Cuddling.lasts(random);
        assertThat(lasts).isBetween(400L, 1200L);
    }
}
