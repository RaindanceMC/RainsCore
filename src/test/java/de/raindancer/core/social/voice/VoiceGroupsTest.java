package de.raindancer.core.social.voice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("voice groups a game can ask for")
class VoiceGroupsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());

    private static final class Recording implements VoiceGroups.Provider {
        final List<String> said = new ArrayList<>();

        @Override
        public void place(String key, String name, Collection<UUID> players) {
            said.add("place " + key + " " + name + " " + players.size());
        }

        @Override
        public void disband(String key) {
            said.add("disband " + key);
        }
    }

    @AfterEach
    void tearDown() {
        VoiceGroups.clear();
    }

    @Test
    @DisplayName("without a provider nothing happens and nothing fails")
    void nobody() {
        assertThat(VoiceGroups.isAvailable()).isFalse();
        VoiceGroups.place("ctf:red", "Red", Set.of(ALICE));
        VoiceGroups.disband("ctf:red");
    }

    @Test
    @DisplayName("with one, the asks reach it")
    void reaches() {
        Recording voice = new Recording();
        VoiceGroups.register("voicebridge", voice);
        VoiceGroups.place("ctf:red", "Red", Set.of(ALICE));
        VoiceGroups.disband("ctf:red");
        assertThat(VoiceGroups.isAvailable()).isTrue();
        assertThat(voice.said).containsExactly("place ctf:red Red 1", "disband ctf:red");
    }

    @Test
    @DisplayName("a provider that throws is skipped, not fatal to the game asking")
    void throwing() {
        VoiceGroups.register("broken", new VoiceGroups.Provider() {
            @Override
            public void place(String key, String name, Collection<UUID> players) {
                throw new IllegalStateException("no mod");
            }

            @Override
            public void disband(String key) {
                throw new IllegalStateException("no mod");
            }
        });
        VoiceGroups.place("ctf:red", "Red", Set.of(ALICE));
        VoiceGroups.disband("ctf:red");
    }

    @Test
    @DisplayName("unregistered, it is gone")
    void unregister() {
        VoiceGroups.register("voicebridge", new Recording());
        assertThat(VoiceGroups.unregister("voicebridge")).isTrue();
        assertThat(VoiceGroups.isAvailable()).isFalse();
    }
}
