package de.raindancer.core.ui.choose;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("which particles a player may put on what they own")
class ParticleGrantsTest {

    @AfterEach
    void clear() {
        ParticleGrants.clear();
    }

    @Test
    @DisplayName("nobody answering is empty; a provider answers, the latest wins, and retracting stops it")
    void provided() {
        Player alice = mock(Player.class);
        assertThat(ParticleGrants.of(alice)).isEmpty();
        Object first = ParticleGrants.provide(who -> List.of("HEART"));
        Object second = ParticleGrants.provide(who -> List.of("FLAME", "HEART"));
        assertThat(ParticleGrants.of(alice)).contains(List.of("FLAME", "HEART"));
        ParticleGrants.retract(second);
        assertThat(ParticleGrants.of(alice)).contains(List.of("HEART"));
        ParticleGrants.retract(first);
        assertThat(ParticleGrants.of(alice)).isEmpty();
    }
}
