package de.raindancer.core.platform.command;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.ui.identity.Nicknames;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
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
import static org.mockito.Mockito.when;

/**
 * One lookup for every command: selectors, names and nicknames — and always the offline as well, so a
 * command can say "they are offline" rather than "nobody is called that".
 */
@org.junit.jupiter.api.Tag("database")
class PlayerLookupTest {

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final CommandSender sender = mock(CommandSender.class);
    private Database database;
    private Nicknames nicknames;
    private Player lilly;
    private Player sam;
    private OfflinePlayer ghost;

    @BeforeEach
    void setUp() {
        database = TestDatabases.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        nicknames = new Nicknames(database);
        PlayerTargets.useNicknames(nicknames);
        lilly = online("lillyyxoxo");
        sam = online("Sam");
        doReturn(List.of(lilly, sam)).when(server).getOnlinePlayers();
        ghost = mock(OfflinePlayer.class);
        UUID ghostId = UUID.nameUUIDFromBytes("ghost".getBytes());
        when(ghost.getUniqueId()).thenReturn(ghostId);
        when(ghost.getName()).thenReturn("OldGhost");
        when(server.getOfflinePlayer(ghostId)).thenReturn(ghost);
        when(server.getOfflinePlayerIfCached("OldGhost")).thenReturn(ghost);
        when(server.getOfflinePlayers()).thenReturn(new OfflinePlayer[]{ghost});
        nicknames.remember(lilly.getUniqueId(), "Lilly Pad");
        nicknames.remember(ghostId, "Casper");
        when(sender.hasPermission("minecraft.command.selector")).thenReturn(true);
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
    @DisplayName("a selector finds every player it matches, and says it was a selector")
    void selector() {
        doReturn(List.<Entity>of(lilly, sam)).when(server).selectEntities(sender, "@a");

        PlayerLookup found = PlayerTargets.lookup(server, sender, "@a");

        assertThat(found.kind()).isEqualTo(PlayerLookup.Kind.SELECTOR);
        assertThat(found.online()).containsExactly(lilly, sam);
        assertThat(found.single()).isEmpty();
    }

    @Test
    @DisplayName("a selector matching one player is that player wherever one is wanted")
    void oneFromASelector() {
        doReturn(List.<Entity>of(sam)).when(server).selectEntities(sender, "@p");

        assertThat(PlayerTargets.online(server, sender, "@p")).contains(sam);
        assertThat(PlayerTargets.find(server, sender, "@p")).contains(sam);
    }

    @Test
    @DisplayName("a selector the sender may not use matches nobody, and says why")
    void selectorNotAllowed() {
        when(sender.hasPermission("minecraft.command.selector")).thenReturn(false);
        when(server.selectEntities(any(), anyString())).thenThrow(new IllegalArgumentException("no"));

        PlayerLookup found = PlayerTargets.lookup(server, sender, "@a");

        assertThat(found.isEmpty()).isTrue();
        assertThat(found.kind()).isEqualTo(PlayerLookup.Kind.SELECTOR_REFUSED);
    }

    @Test
    @DisplayName("names and nicknames, online and offline, each say which they were")
    void namesAndNicknames() {
        assertThat(PlayerTargets.lookup(server, sender, "Sam").kind()).isEqualTo(PlayerLookup.Kind.NAME);
        assertThat(PlayerTargets.lookup(server, sender, "Lilly_Pad").kind()).isEqualTo(PlayerLookup.Kind.NICKNAME);

        PlayerLookup offlineName = PlayerTargets.lookup(server, sender, "OldGhost");
        assertThat(offlineName.matches()).containsExactly(ghost);
        assertThat(offlineName.online()).isEmpty();
        assertThat(offlineName.isOfflineOnly()).isTrue();

        PlayerLookup offlineNick = PlayerTargets.lookup(server, sender, "casper");
        assertThat(offlineNick.matches()).containsExactly(ghost);
        assertThat(offlineNick.kind()).isEqualTo(PlayerLookup.Kind.NICKNAME);
    }

    @Test
    @DisplayName("nobody is nobody")
    void nobody() {
        PlayerLookup found = PlayerTargets.lookup(server, sender, "Nobody");

        assertThat(found.isEmpty()).isTrue();
        assertThat(found.kind()).isEqualTo(PlayerLookup.Kind.NONE);
    }

    @Test
    @DisplayName("suggestions always carry the offline too — names and nicknames — after the online")
    void suggestionsIncludeOffline() {
        List<String> all = PlayerTargets.suggest(server, sender, "", who -> true);

        assertThat(all).contains("@a", "lillyyxoxo", "Lilly_Pad", "Sam", "OldGhost", "Casper");
        assertThat(all.indexOf("Sam")).isLessThan(all.indexOf("OldGhost"));
    }

    @Test
    @DisplayName("selectors are only offered to somebody who may use them")
    void selectorsOnlyWhenAllowed() {
        when(sender.hasPermission("minecraft.command.selector")).thenReturn(false);

        assertThat(PlayerTargets.suggest(server, sender, "", who -> true)).noneMatch(PlayerTargets::isSelector);
    }
}
