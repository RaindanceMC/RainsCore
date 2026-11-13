package de.raindancer.core.social.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrimeCommandTest {

    @Test
    @DisplayName("a UUID is taken with or without dashes — Tebex's {id} has none")
    void uuids() {
        UUID id = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assertThat(PrimeCommand.target("069a79f4-44e9-4726-a5be-fca90e38aaf5")).contains(id);
        assertThat(PrimeCommand.target("069a79f444e94726a5befca90e38aaf5")).contains(id);
    }

    @Test
    @DisplayName("days are a whole number from 1 to 3660, a trailing d allowed")
    void days() {
        assertThat(PrimeCommand.days("31")).contains(Duration.ofDays(31));
        assertThat(PrimeCommand.days("30d")).contains(Duration.ofDays(30));
        assertThat(PrimeCommand.days("0")).isEmpty();
        assertThat(PrimeCommand.days("-3")).isEmpty();
        assertThat(PrimeCommand.days("month")).isEmpty();
        assertThat(PrimeCommand.days("99999")).isEmpty();
    }
}
