package de.raindancer.core.world.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("companions: a mob a plugin bound to a player")
class CompanionsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID FOX = UUID.nameUUIDFromBytes("fox".getBytes());

    @Test
    @DisplayName("a bound mob answers its owner until it is released")
    void bindAndRelease() {
        Companions companions = new Companions();
        assertThat(companions.ownerOf(FOX)).isEmpty();

        companions.bind(FOX, ALICE);
        assertThat(companions.ownerOf(FOX)).contains(ALICE);
        assertThat(companions.isCompanion(FOX)).isTrue();
        assertThat(companions.of(ALICE)).containsExactly(FOX);

        companions.release(FOX);
        assertThat(companions.ownerOf(FOX)).isEmpty();
        assertThat(companions.of(ALICE)).isEmpty();
    }

    @Test
    @DisplayName("nulls are nobody's, never an exception on the damage path")
    void nulls() {
        Companions companions = new Companions();
        companions.bind(null, ALICE);
        companions.bind(FOX, null);
        companions.release(null);
        assertThat(companions.ownerOf(null)).isEmpty();
        assertThat(companions.isCompanion(FOX)).isFalse();
    }

    @Test
    @DisplayName("releasing everything of an owner frees all their mobs")
    void releaseAllOf() {
        Companions companions = new Companions();
        UUID wolf = UUID.randomUUID();
        companions.bind(FOX, ALICE);
        companions.bind(wolf, ALICE);
        assertThat(companions.releaseAllOf(ALICE)).isEqualTo(2);
        assertThat(companions.isCompanion(FOX)).isFalse();
        assertThat(companions.isCompanion(wolf)).isFalse();
    }
}
