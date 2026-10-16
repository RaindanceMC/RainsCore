package de.raindancer.core.world.spawn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("pickups that come back after a while")
class PickupsTest {

    private final AtomicLong now = new AtomicLong(0);
    private Pickups pickups;

    @BeforeEach
    void setUp() {
        pickups = new Pickups(now::get);
        pickups.place(new Pickups.Pickup("mid", "speed", "ctf", 10.5, 64, -3.5, Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("a placed pickup is there, and taking it takes it")
    void take() {
        assertThat(pickups.available()).extracting(Pickups.Pickup::id).containsExactly("mid");
        assertThat(pickups.take("mid")).isPresent();
        assertThat(pickups.available()).isEmpty();
        assertThat(pickups.take("mid")).as("nobody takes the same one twice").isEmpty();
    }

    @Test
    @DisplayName("it comes back once its time is up — reported once")
    void comesBack() {
        pickups.take("mid");
        now.addAndGet(29_999);
        assertThat(pickups.dueBack()).isEmpty();
        now.addAndGet(1);
        assertThat(pickups.dueBack()).extracting(Pickups.Pickup::id).containsExactly("mid");
        assertThat(pickups.dueBack()).isEmpty();
        assertThat(pickups.available()).hasSize(1);
    }

    @Test
    @DisplayName("the one nearest to a player, within reach, is found")
    void nearest() {
        pickups.place(new Pickups.Pickup("far", "heal", "ctf", 40, 64, 40, Duration.ofSeconds(5)));
        assertThat(pickups.within("ctf", 11, 64.5, -3, 1.5)).map(Pickups.Pickup::id).contains("mid");
        assertThat(pickups.within("ctf", 20, 64, 20, 1.5)).isEmpty();
        assertThat(pickups.within("other", 10.5, 64, -3.5, 1.5)).as("another world").isEmpty();
    }

    @Test
    @DisplayName("time left until it is back")
    void timeLeft() {
        pickups.take("mid");
        now.addAndGet(10_000);
        assertThat(pickups.backIn("mid")).contains(Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("clearing removes all")
    void clear() {
        pickups.clear();
        assertThat(pickups.all()).isEmpty();
    }
}
