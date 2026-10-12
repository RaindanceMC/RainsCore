package de.raindancer.core.platform.command;

import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** @DAWN — a guild's tag, or any other group a plugin offers — means its members, in every command that takes players. */
class PlayerGroupsTest {

    private final Server server = mock(Server.class);
    private final CommandSender sender = mock(CommandSender.class);
    private final UUID ada = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final UUID cy = UUID.randomUUID();

    @AfterEach
    void clear() {
        PlayerGroups.clear();
    }

    private Player online(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(server.getPlayer(id)).thenReturn(player);
        return player;
    }

    @Test
    @DisplayName("@TAG is a group's members — online ones as players, the rest as known — case does not matter")
    void group() {
        Player adaOnline = online(ada, "Ada");
        OfflinePlayer cyOffline = mock(OfflinePlayer.class);
        when(server.getOfflinePlayer(cy)).thenReturn(cyOffline);
        PlayerGroups.register("guilds", name -> name.equalsIgnoreCase("DAWN") ? Optional.of(List.of(ada, cy))
                : Optional.empty(), () -> List.of("DAWN"));

        PlayerLookup found = PlayerTargets.lookup(server, sender, "@dawn");
        assertThat(found.kind()).isEqualTo(PlayerLookup.Kind.GROUP);
        assertThat(found.matches()).containsExactly(adaOnline, cyOffline);
        assertThat(PlayerTargets.resolve(server, sender, "@DAWN")).containsExactly(adaOnline);
        verify(server, never()).selectEntities(any(), anyString());
    }

    @Test
    @DisplayName("a group needs no selector permission; the vanilla ones still go to the game's parser")
    void notASelector() {
        when(sender.hasPermission(PlayerTargets.SELECTOR_NODE)).thenReturn(false);
        online(bo, "Bo");
        PlayerGroups.register("guilds", name -> Optional.of(List.of(bo)), List::of);
        assertThat(PlayerTargets.lookup(server, sender, "@ELF").kind()).isEqualTo(PlayerLookup.Kind.GROUP);
        assertThat(PlayerTargets.lookup(server, sender, "@a").kind()).isEqualTo(PlayerLookup.Kind.SELECTOR_REFUSED);
        assertThat(PlayerTargets.lookup(server, sender, "@e[type=player]").kind()).isEqualTo(PlayerLookup.Kind.SELECTOR_REFUSED);
    }

    @Test
    @DisplayName("an @word no group knows matches nobody; a throwing group is skipped")
    void unknown() {
        PlayerGroups.register("broken", name -> {
            throw new IllegalStateException("gone");
        }, List::of);
        PlayerLookup found = PlayerTargets.lookup(server, sender, "@NOPE");
        assertThat(found.kind()).isEqualTo(PlayerLookup.Kind.GROUP);
        assertThat(found.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("the groups are offered when typing @")
    void suggested() {
        PlayerGroups.register("guilds", name -> Optional.empty(), () -> List.of("DAWN", "ELF"));
        assertThat(PlayerGroups.suggest("@d")).containsExactly("@DAWN");
        assertThat(PlayerGroups.suggest("@")).containsExactly("@DAWN", "@ELF");
        assertThat(PlayerGroups.suggest("da")).isEmpty();
    }
}
