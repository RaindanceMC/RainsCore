package de.raindancer.core.world.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("what a pet remembers of its owner's fights")
class GrudgesTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID ZOMBIE = UUID.randomUUID();

    @Test
    @DisplayName("a hit is remembered for a while, then forgotten")
    void memory() {
        Grudges grudges = new Grudges();
        grudges.hurt(ALICE, ZOMBIE, 1_000);
        assertThat(grudges.hurtOwner(ALICE, ZOMBIE, 1_000 + Grudges.MEMORY)).isTrue();
        assertThat(grudges.hurtBy(ALICE, 1_001 + Grudges.MEMORY)).isEmpty();

        grudges.struck(ALICE, ZOMBIE, 5_000);
        assertThat(grudges.ownerStruck(ALICE, ZOMBIE, 5_500)).isTrue();
        grudges.forget(ALICE);
        assertThat(grudges.struck(ALICE, 5_500)).isEmpty();
    }
}
