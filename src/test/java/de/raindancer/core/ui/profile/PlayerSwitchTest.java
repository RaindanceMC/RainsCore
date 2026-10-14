package de.raindancer.core.ui.profile;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A player's own on/off for one thing, kept with the player. */
@DisplayName("a player's own switch")
class PlayerSwitchTest {

    private final Map<NamespacedKey, Boolean> stored = new HashMap<>();

    private Player player() {
        Player player = mock(Player.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(player.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.BOOLEAN)))
                .thenAnswer(call -> stored.get(call.getArgument(0, NamespacedKey.class)));
        doAnswer(call -> stored.put(call.getArgument(0), call.getArgument(2)))
                .when(data).set(any(NamespacedKey.class), eq(PersistentDataType.BOOLEAN), any(Boolean.class));
        doAnswer(call -> stored.remove(call.getArgument(0, NamespacedKey.class)))
                .when(data).remove(any(NamespacedKey.class));
        return player;
    }

    @Test
    @DisplayName("somebody who never touched it gets the default")
    void defaultUntilTouched() {
        Player player = player();

        assertThat(new PlayerSwitch("manhunt", "trail", true).isOn(player)).isTrue();
        assertThat(new PlayerSwitch("manhunt", "other", false).isOn(player)).isFalse();
    }

    @Test
    @DisplayName("toggling flips it and says what it is now, and it stays that way")
    void toggles() {
        Player player = player();
        PlayerSwitch trail = new PlayerSwitch("manhunt", "trail", true);

        assertThat(trail.toggle(player)).isFalse();
        assertThat(trail.isOn(player)).isFalse();
        assertThat(trail.toggle(player)).isTrue();
        trail.set(player, false);
        assertThat(trail.isOn(player)).isFalse();
    }

    @Test
    @DisplayName("stored with every other switch in Core's one namespace, which follows the player across the network")
    void namespaced() {
        assertThat(new PlayerSwitch("manhunt", "trail", true).key())
                .isEqualTo(new NamespacedKey(PlayerSwitch.NAMESPACE, "manhunt.trail"));
    }

    @Test
    @DisplayName("what a player chose before 2.0 (under the owner's namespace) still counts, and moves on the next change")
    void oldChoiceKept() {
        Player player = player();
        stored.put(new NamespacedKey("manhunt", "switch-trail"), false);
        PlayerSwitch trail = new PlayerSwitch("manhunt", "trail", true);

        assertThat(trail.isOn(player)).isFalse();
        trail.set(player, true);
        assertThat(stored).doesNotContainKey(new NamespacedKey("manhunt", "switch-trail"))
                .containsEntry(trail.key(), true);
    }

    @Test
    @DisplayName("an owner or name that is not a valid key is refused up front")
    void refusesNonsense() {
        assertThatThrownBy(() -> new PlayerSwitch("Man Hunt", "trail", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlayerSwitch("manhunt", "", true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
