package de.raindancer.core.platform.command;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.ui.identity.Nicknames;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pointing a command at somebody by their nickname — online or not — and being offered it while typing.
 */
@org.junit.jupiter.api.Tag("database")
class PlayerTargetsNicknameTest {

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final CommandSender sender = mock(CommandSender.class);
    private Database database;
    private Nicknames nicknames;

    private Player lilly;
    private Player sam;

    @BeforeEach
    void setUp() {
        database = TestDatabases.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        nicknames = new Nicknames(database);
        PlayerTargets.useNicknames(nicknames);
        lilly = online("lillyyxoxo");
        sam = online("Sam");
        doReturn(List.of(lilly, sam)).when(server).getOnlinePlayers();
        nicknames.remember(lilly.getUniqueId(), "Lilly Pad");
    }

    @AfterEach
    void tearDown() {
        PlayerTargets.useNicknames(null);
        database.close();
    }

    private Player online(String name) {
        Player player = mock(Player.class);
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(server.getPlayerExact(name)).thenReturn(player);
        when(server.getPlayer(id)).thenReturn(player);
        return player;
    }

    @Test
    @DisplayName("a nickname resolves to its online owner, and never asks the selector parser")
    void resolvesANickname() {
        assertThat(PlayerTargets.resolve(server, sender, "Lilly_Pad")).containsExactly(lilly);
        assertThat(PlayerTargets.resolve(server, sender, "lilly_pad")).containsExactly(lilly);
        verify(server, never()).selectEntities(any(), anyString());
    }

    @Test
    @DisplayName("a real name wins over somebody else's nickname that reads the same")
    void realNamesFirst() {
        nicknames.remember(lilly.getUniqueId(), "Sam");

        assertThat(PlayerTargets.resolve(server, sender, "Sam")).containsExactly(sam);
    }

    @Test
    @DisplayName("a nickname can never stand in for a real player who is offline: their real name wins")
    void offlineRealNameWins() {
        OfflinePlayer griefer = mock(OfflinePlayer.class);
        when(griefer.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes("griefer".getBytes()));
        when(server.getOfflinePlayerIfCached("Griefer")).thenReturn(griefer);
        nicknames.remember(sam.getUniqueId(), "Griefer");

        assertThat(PlayerTargets.find(server, "Griefer")).contains(griefer);
        assertThat(PlayerTargets.resolve(server, sender, "Griefer")).isEmpty();
        assertThat(PlayerTargets.online(server, "Griefer")).isEmpty();
        assertThat(PlayerTargets.isRealName(server, "Griefer")).isTrue();
    }

    @Test
    @DisplayName("an offline owner is not 'resolved' — resolve is for acting on somebody who is here")
    void offlineIsNotResolved() {
        UUID gone = UUID.nameUUIDFromBytes("gone".getBytes());
        nicknames.remember(gone, "Ghost");

        assertThat(PlayerTargets.resolve(server, sender, "Ghost")).isEmpty();
    }

    @Test
    @DisplayName("find: online by name, online by nickname, offline by nickname, offline by cached name")
    void find() {
        UUID gone = UUID.nameUUIDFromBytes("gone".getBytes());
        OfflinePlayer ghost = mock(OfflinePlayer.class);
        when(ghost.getUniqueId()).thenReturn(gone);
        when(server.getOfflinePlayer(gone)).thenReturn(ghost);
        nicknames.remember(gone, "Ghost");
        OfflinePlayer cached = mock(OfflinePlayer.class);
        when(server.getOfflinePlayerIfCached("OldTimer")).thenReturn(cached);

        assertThat(PlayerTargets.find(server, "Sam")).contains(sam);
        assertThat(PlayerTargets.find(server, "lilly_pad")).contains(lilly);
        assertThat(PlayerTargets.find(server, "ghost")).contains(ghost);
        assertThat(PlayerTargets.find(server, "OldTimer")).contains(cached);
        assertThat(PlayerTargets.find(server, "nobody")).isEmpty();
        assertThat(PlayerTargets.find(server, " ")).isEmpty();
    }

    @Test
    @DisplayName("suggestions carry the nicknames of whoever is online, written as they would be typed")
    void suggestsNicknames() {
        assertThat(PlayerTargets.suggest(server, "")).contains("lillyyxoxo", "Lilly_Pad", "Sam");
        assertThat(PlayerTargets.suggest(server, "lilly_")).containsExactly("Lilly_Pad");
    }

    @Test
    @DisplayName("an offline player's nickname is offered too — resolution always reaches the offline")
    void onlineSuggestionsIncludeOffline() {
        nicknames.remember(UUID.nameUUIDFromBytes("gone".getBytes()), "Ghost");

        assertThat(PlayerTargets.suggest(server, "gh")).containsExactly("Ghost");
        assertThat(PlayerTargets.suggestKnown(server, "gh", who -> true)).containsExactly("Ghost");
    }

    @Test
    @DisplayName("a hidden player is offered only the way an offline one is: after everybody visible")
    void hiddenIsNotOfferedAsOnline() {
        List<String> offered = PlayerTargets.suggest(server, "", who -> !who.equals(lilly));

        assertThat(offered.indexOf("Sam")).isLessThan(offered.indexOf("Lilly_Pad"));
    }

    @Test
    @DisplayName("the name to show for somebody is their nickname when they have one")
    void shownName() {
        assertThat(PlayerTargets.shownName(lilly)).isEqualTo("Lilly Pad");
        assertThat(PlayerTargets.shownName(sam)).isEqualTo("Sam");
    }

    @Test
    @DisplayName("without a directory, everything still works on real names")
    void noDirectory() {
        PlayerTargets.useNicknames(null);

        assertThat(PlayerTargets.resolve(server, sender, "Sam")).containsExactly(sam);
        assertThat(PlayerTargets.resolve(server, sender, "Lilly_Pad")).isEmpty();
        assertThat(PlayerTargets.suggest(server, "")).doesNotContain("Lilly_Pad");
    }
}
