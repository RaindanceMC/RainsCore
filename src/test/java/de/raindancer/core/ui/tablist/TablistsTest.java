package de.raindancer.core.ui.tablist;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.ui.identity.Identities;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one thing {@code TablistTest} deliberately leaves out: what {@link Tablists#refresh()} does
 * with the packets themselves, once who is hidden is known.
 *
 * <h2>The bug this exists to catch</h2>
 * A vanished player was sent no header or footer at all while hidden — {@code refresh()} used to
 * build both the packet's <em>content</em> and its <em>recipient list</em> from the same
 * vanish-filtered set of players, so the one player who is IN that filtered-out set never received
 * another update for as long as they stayed hidden. Their own tablist froze at whatever count was
 * last true the moment before they vanished, and un-vanishing simply resumed updates from there —
 * which reads as "nothing changed", exactly the report this was written against: the header still
 * said the old count after toggling vanish off, because it had never once been wrong, only stale.
 *
 * <p>The fix keeps the count correct — nobody vanished is ever counted, for anybody, including
 * themselves — while still sending every online player, vanished or not, a fresh header and footer
 * on every refresh. Being hidden should not also mean being forgotten.
 */
@org.junit.jupiter.api.Tag("database")
class TablistsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private Database database;
    private Identities identities;
    private TablistModel model;
    private Tablists tablists;

    @TempDir
    Path directory;

    @BeforeEach
    void setUp() {
        database = TestDatabases.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        identities = new Identities(database);
        model = new TablistModel(identities);
        tablists = new Tablists(model, "Rain's SMP");
        // The scoreboard-team sorting is Tablists' other job and is not what this class is about —
        // see TablistTest's own note on why sending needs a server and deciding does not. Switching
        // it off here sidesteps mocking a Scoreboard just to test who gets sent what.
        tablists.groupByWorld(false);
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    private static Player fakePlayer(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        when(player.getPing()).thenReturn(20);
        return player;
    }

    private static String plain(Component component) {
        return plainText().serialize(component);
    }

    @Test
    @DisplayName("a vanished player still receives a fresh header and footer")
    void vanishedPlayersAreNotForgotten() {
        Player alice = fakePlayer(ALICE, "Alice");
        Player bob = fakePlayer(BOB, "Bob");
        tablists.hiddenPlayers(() -> Set.of(ALICE));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(alice, bob));
            tablists.refresh();
        }

        // Alice is hidden from the *count*, not from ever hearing from the tablist again — that gap
        // is exactly what left her own header frozen on a live server.
        verify(alice).sendPlayerListHeaderAndFooter(any(), any());
        verify(bob).sendPlayerListHeaderAndFooter(any(), any());
    }

    @Test
    @DisplayName("the header everybody receives, including the vanished player, excludes them from the count")
    void theCountExcludesEverybodyHidden() {
        Player alice = fakePlayer(ALICE, "Alice");
        Player bob = fakePlayer(BOB, "Bob");
        tablists.hiddenPlayers(() -> Set.of(ALICE));

        ArgumentCaptor<Component> aliceHeader = ArgumentCaptor.forClass(Component.class);
        ArgumentCaptor<Component> bobHeader = ArgumentCaptor.forClass(Component.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(alice, bob));
            tablists.refresh();
        }

        verify(alice).sendPlayerListHeaderAndFooter(aliceHeader.capture(), any());
        verify(bob).sendPlayerListHeaderAndFooter(bobHeader.capture(), any());

        // One player online, not two — from anybody's screen, including the vanished player's own.
        // Seeing herself counted would say the promise vanish makes does not hold for her either.
        assertThat(plain(aliceHeader.getValue())).contains("1").doesNotContain("2");
        assertThat(plain(bobHeader.getValue())).contains("1").doesNotContain("2");
    }

    @Test
    @DisplayName("a player's line is only sent when it differs from the one they already have")
    void anUnchangedLineIsNotResent() {
        Player alice = fakePlayer(ALICE, "Alice");
        ArgumentCaptor<Component> line = ArgumentCaptor.forClass(Component.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(alice));
            tablists.refresh();
            verify(alice).playerListName(line.capture());
            when(alice.playerListName()).thenReturn(line.getValue());

            tablists.refresh();
        }

        // Each call is a packet to every player on the server; unchanged, it says nothing.
        verify(alice, org.mockito.Mockito.times(1)).playerListName(any(Component.class));
    }

    @Test
    @DisplayName("with nobody hidden, everybody sees the real count")
    void ordinaryRefreshCountsEverybody() {
        Player alice = fakePlayer(ALICE, "Alice");
        Player bob = fakePlayer(BOB, "Bob");

        ArgumentCaptor<Component> header = ArgumentCaptor.forClass(Component.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(alice, bob));
            tablists.refresh();
        }

        verify(alice).sendPlayerListHeaderAndFooter(header.capture(), any());
        assertThat(plain(header.getValue())).contains("2");
    }

    @Test
    @DisplayName("switched off, nobody is sent anything at all")
    void disabledSendsNothing() {
        Player alice = fakePlayer(ALICE, "Alice");

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(alice));
            // enabled(false) reaches for Bukkit.getOnlinePlayers() itself, on the way down, to
            // restore plain names — see its own note — so it has to run inside the same mocked
            // static block as refresh() rather than before it. That restore is itself one call to
            // sendPlayerListHeaderAndFooter (with empty components); cleared here so the assertion
            // below is only about what refresh() itself does once disabled, not about the restore.
            tablists.enabled(false);
            org.mockito.Mockito.clearInvocations(alice);
            tablists.refresh();
        }

        verify(alice, org.mockito.Mockito.never()).sendPlayerListHeaderAndFooter(any(), any());
    }
}
