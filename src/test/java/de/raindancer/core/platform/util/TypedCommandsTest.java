package de.raindancer.core.platform.util;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TypedCommandsTest {

    private final Player player = mock(Player.class);

    {
        // The event asks the player's server who is online, for its recipients.
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        org.mockito.Mockito.when(player.getServer()).thenReturn(server);
        org.mockito.Mockito.doReturn(java.util.List.of()).when(server).getOnlinePlayers();
    }

    @Test
    @DisplayName("a command a menu runs passes the same gate as a typed one: cancelled, it never runs")
    void cancelled() {
        boolean ran = TypedCommands.run(player, "shop", event -> event.setCancelled(true));
        assertThat(ran).isFalse();
        verify(player, never()).performCommand(anyString());
    }

    @Test
    @DisplayName("let through, it runs — as a listener may have rewritten it, without the slash")
    void runs() {
        boolean ran = TypedCommands.run(player, "/shop", event -> {
            assertThat(event.getMessage()).isEqualTo("/shop");
            event.setMessage("/shop ores");
        });
        assertThat(ran).isTrue();
        verify(player).performCommand("shop ores");
    }

    @Test
    @DisplayName("the event is the one typing fires, for this player")
    void sameEvent() {
        PlayerCommandPreprocessEvent[] seen = new PlayerCommandPreprocessEvent[1];
        TypedCommands.run(player, "island menu", event -> seen[0] = event);
        assertThat(seen[0].getPlayer()).isSameAs(player);
        assertThat(seen[0].getMessage()).isEqualTo("/island menu");
        verify(player).performCommand("island menu");
    }
}
