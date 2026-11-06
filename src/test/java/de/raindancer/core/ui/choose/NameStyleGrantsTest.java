package de.raindancer.core.ui.choose;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("what styles a player may paint names they own with")
class NameStyleGrantsTest {

    @AfterEach
    void clear() {
        NameStyleGrants.clear();
    }

    @Test
    @DisplayName("nobody answering is an empty answer, so the asker picks its own fallback")
    void nobody() {
        assertThat(NameStyleGrants.of(mock(Player.class))).isEmpty();
    }

    @Test
    @DisplayName("the plugin that styles players' names answers for anything else they name")
    void provided() {
        Player alice = mock(Player.class);
        Object handle = NameStyleGrants.provide(who -> who == alice ? StyleGrants.ALL : StyleGrants.NOTHING);
        assertThat(NameStyleGrants.of(alice)).contains(StyleGrants.ALL);
        assertThat(NameStyleGrants.of(mock(Player.class))).contains(StyleGrants.NOTHING);

        NameStyleGrants.retract(handle);
        assertThat(NameStyleGrants.of(alice)).isEmpty();
    }

    @Test
    @DisplayName("the latest to provide answers")
    void latest() {
        NameStyleGrants.provide(who -> StyleGrants.NOTHING);
        NameStyleGrants.provide(who -> StyleGrants.ALL);
        assertThat(NameStyleGrants.of(mock(Player.class))).contains(StyleGrants.ALL);
    }
}
