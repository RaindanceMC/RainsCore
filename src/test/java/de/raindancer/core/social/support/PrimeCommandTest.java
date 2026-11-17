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

    @Test
    @DisplayName("somebody bought Prime before ever joining: their name is looked up with Mojang, not refused")
    void unknownNameIsLookedUp() {
        UUID ada = UUID.randomUUID();
        java.util.List<String> asked = new java.util.ArrayList<>();
        var found = PrimeCommand.resolve("Ada_123", typed -> java.util.Optional.empty(), typed -> {
            asked.add(typed);
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.of(ada));
        }).join();
        assertThat(found).contains(ada);
        assertThat(asked).containsExactly("Ada_123");
    }

    @Test
    @DisplayName("a UUID or a name the server knows is never sent to Mojang; nonsense is not looked up at all")
    void knownAndNonsense() {
        UUID ada = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        java.util.List<String> asked = new java.util.ArrayList<>();
        java.util.function.Function<String, java.util.concurrent.CompletableFuture<java.util.Optional<UUID>>> mojang =
                typed -> {
                    asked.add(typed);
                    return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
                };
        assertThat(PrimeCommand.resolve("069a79f444e94726a5befca90e38aaf5", typed -> java.util.Optional.empty(), mojang)
                .join()).contains(ada);
        assertThat(PrimeCommand.resolve("Ada", typed -> java.util.Optional.of(ada), mojang).join()).contains(ada);
        assertThat(PrimeCommand.resolve("not a name!", typed -> java.util.Optional.empty(), mojang).join()).isEmpty();
        assertThat(asked).isEmpty();
    }
}
