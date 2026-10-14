package de.raindancer.core.ui.tablist;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.ui.identity.Identities;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The tablist: who is on, and where they are.
 *
 * <h2>What is being tested</h2>
 * Not the packets — those need a server, and there is a live check for them. What is here is
 * everything that decides <em>what it says</em>: how players are grouped by world, what order the
 * groups come in, how a name is built out of somebody's prefix and world, and what the header and
 * footer say. Each of those is a decision somebody will want to change, and none of them should
 * need a server to get right.
 */
@org.junit.jupiter.api.Tag("database")
class TablistTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final UUID CAROL = UUID.nameUUIDFromBytes("carol".getBytes());

    private Database openedDatabase;

    /** One database per test, opened on first use so the temporary directory already exists. */
    private Database database() {
        if (openedDatabase == null || !openedDatabase.isUsable()) {
            openedDatabase = TestDatabases.open(directory.resolve("core.db"), CoreSchema.CORE,
                    () -> false);
        }
        return openedDatabase;
    }

    @org.junit.jupiter.api.AfterEach
    void closeDatabase() {
        if (openedDatabase != null) {
            openedDatabase.close();
        }
    }

    @TempDir
    Path directory;
    private Identities identities;
    private TablistModel model;

    @BeforeEach
    void setUp() {
        identities = new Identities(database());
        model = new TablistModel(identities);
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static TablistEntry entry(UUID id, String name, String world) {
        return new TablistEntry(id, name, world, 42);
    }

    // ------------------------------------------------------------------ which world

    @Nested
    @DisplayName("showing which world somebody is in")
    class Worlds {

        @Test
        @DisplayName("a world's name is turned into something a person would say")
        void readableWorldNames() {
            assertThat(TablistModel.worldLabel("world")).isEqualTo("Overworld");
            assertThat(TablistModel.worldLabel("world_nether")).isEqualTo("Nether");
            assertThat(TablistModel.worldLabel("world_the_end")).isEqualTo("The End");
        }

        @Test
        @DisplayName("a world nobody named specially still reads well")
        void unknownWorldNames() {
            assertThat(TablistModel.worldLabel("farmworld")).isEqualTo("Farmworld");
            assertThat(TablistModel.worldLabel("farm_world")).isEqualTo("Farm world");
            assertThat(TablistModel.worldLabel("farmworld_nether")).isEqualTo("Farmworld Nether");
            assertThat(TablistModel.worldLabel("farmworld_the_end")).isEqualTo("Farmworld End");
        }

        @Test
        @DisplayName("a missing world does not produce a blank label")
        void survivesNoWorld() {
            assertThat(TablistModel.worldLabel(null)).isNotBlank();
            assertThat(TablistModel.worldLabel("")).isNotBlank();
        }

        @Test
        @DisplayName("every world has its own symbol, so the list scans without reading")
        void worldsHaveSymbols() {
            assertThat(TablistModel.worldSymbol("world"))
                    .isNotEqualTo(TablistModel.worldSymbol("world_nether"));
            assertThat(TablistModel.worldSymbol("world_the_end"))
                    .isNotEqualTo(TablistModel.worldSymbol("world"));
            assertThat(TablistModel.worldSymbol("farmworld")).isNotBlank();
        }
    }

    // ------------------------------------------------------------------ grouping

    @Nested
    @DisplayName("grouping by world")
    class Grouping {

        @Test
        @DisplayName("players are gathered under the world they are in")
        void groupsByWorld() {
            List<TablistGroup> groups = model.groups(List.of(
                    entry(ALICE, "Raindancer118", "world"),
                    entry(BOB, "Bentex_OG", "world_nether"),
                    entry(CAROL, "Someone", "world")));

            assertThat(groups).hasSize(2);
            assertThat(groups.getFirst().label()).isEqualTo("Overworld");
            assertThat(groups.getFirst().entries()).hasSize(2);
        }

        @Test
        @DisplayName("the overworld comes first, then the nether, then the end")
        void ordersTheVanillaWorlds() {
            List<TablistGroup> groups = model.groups(List.of(
                    entry(ALICE, "A", "world_the_end"),
                    entry(BOB, "B", "world_nether"),
                    entry(CAROL, "C", "world")));

            assertThat(groups).extracting(TablistGroup::label)
                    .containsExactly("Overworld", "Nether", "The End");
        }

        @Test
        @DisplayName("worlds nobody knows come after the vanilla ones, in alphabetical order")
        void ordersTheRest() {
            List<TablistGroup> groups = model.groups(List.of(
                    entry(ALICE, "A", "zebra"),
                    entry(BOB, "B", "world"),
                    entry(CAROL, "C", "aardvark")));

            assertThat(groups).extracting(TablistGroup::label)
                    .containsExactly("Overworld", "Aardvark", "Zebra");
        }

        @Test
        @DisplayName("players are in alphabetical order inside a group")
        void ordersPlayers() {
            List<TablistGroup> groups = model.groups(List.of(
                    entry(ALICE, "zoe", "world"),
                    entry(BOB, "Adam", "world")));

            assertThat(groups.getFirst().entries()).extracting(TablistEntry::name)
                    .containsExactly("Adam", "zoe");
        }

        @Test
        @DisplayName("an empty server is an empty list, not a group of nobody")
        void handlesAnEmptyServer() {
            assertThat(model.groups(List.of())).isEmpty();
            assertThat(model.groups(null)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ what a line says

    @Nested
    @DisplayName("a player's line")
    class Lines {

        @Test
        @DisplayName("is their name when they have nothing else")
        void plainName() {
            assertThat(plain(model.line(entry(ALICE, "Raindancer118", "world"))))
                    .isEqualTo("Raindancer118");
        }

        @Test
        @DisplayName("carries the prefix and suffix they already have, from one declaration")
        void usesTheirIdentity() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            identities.setSuffix(ALICE, " <gray>*");

            assertThat(plain(model.line(entry(ALICE, "Raindancer118", "world"))))
                    .as("a rank set once shows in chat, above the head and here")
                    .isEqualTo("[Admin] Raindancer118 *");
        }

        @Test
        @DisplayName("carries the nametag prefix too, ahead of the chat prefix — an AFK badge "
                + "surviving the periodic rebuild is the whole reason this exists")
        void carriesTheNametagPrefix() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            identities.setNametagPrefix(ALICE, "<gray>[AFK] ");

            assertThat(plain(model.line(entry(ALICE, "Raindancer118", "world"))))
                    .isEqualTo("[AFK] [Admin] Raindancer118");
        }

        @Test
        @DisplayName("can show the world beside the name, for a list that is not grouped")
        void canShowTheWorldInline() {
            assertThat(plain(model.lineWithWorld(entry(ALICE, "Raindancer118", "world_nether"))))
                    .contains("Raindancer118")
                    .contains("Nether");
        }

        @Test
        @DisplayName("a name is never parsed as markup")
        void doesNotParseNames() {
            assertThat(plain(model.line(entry(ALICE, "<red>notacolour", "world"))))
                    .isEqualTo("<red>notacolour");
        }
    }

    // ------------------------------------------------------------------ header and footer

    @Nested
    @DisplayName("the header and footer")
    class HeaderFooter {

        @Test
        @DisplayName("say how many are on and where")
        void summarise() {
            List<TablistEntry> online = List.of(
                    entry(ALICE, "A", "world"),
                    entry(BOB, "B", "world_nether"));

            assertThat(plain(model.header(online, "Rain's SMP")))
                    .contains("Rain's SMP")
                    .contains("2");
            assertThat(plain(model.footer(online)))
                    .as("a per-world count is the whole point of the request")
                    .contains("Overworld")
                    .contains("Nether");
        }

        @Test
        @DisplayName("read sensibly with nobody on")
        void handleAnEmptyServer() {
            assertThat(plain(model.header(List.of(), "Rain's SMP"))).contains("Rain's SMP");
            assertThatCode(() -> model.footer(List.of())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a server with no name still gets a header")
        void survivesNoName() {
            assertThat(plain(model.header(List.of(), null))).isNotBlank();
        }
    }

    // ------------------------------------------------------------------ a written header

    @Nested
    @DisplayName("a header or footer somebody wrote themselves")
    class CustomText {

        private final List<TablistEntry> online = List.of(
                entry(ALICE, "A", "world"),
                entry(BOB, "B", "world_nether"),
                entry(CAROL, "C", "world"));

        @Test
        @DisplayName("how many are on")
        void fillsThePlayerCount() {
            assertThat(plain(model.custom("<players> on", online, "Rain's SMP")))
                    .isEqualTo("3 on");
        }

        @Test
        @DisplayName("what the server is called")
        void fillsTheServerName() {
            assertThat(plain(model.custom("Welcome to <server>", online, "Rain's SMP")))
                    .isEqualTo("Welcome to Rain's SMP");
        }

        @Test
        @DisplayName("how many are in each world — the whole point of the request")
        void fillsTheWorldCounts() {
            assertThat(plain(model.custom("<worlds>", online, "Rain's SMP")))
                    .isEqualTo("Overworld 2 · Nether 1");
        }

        @Test
        @DisplayName("colours work, because it is MiniMessage")
        void keepsColours() {
            assertThat(plain(model.custom("<gold><players></gold> on", online, "x")))
                    .isEqualTo("3 on");
        }

        @Test
        @DisplayName("a mistyped one is shown as written rather than emptying the list")
        void survivesBrokenMarkup() {
            assertThat(plain(model.custom("<notatag>oops", online, "x")))
                    .as("somebody has to be able to see what they typed in order to fix it")
                    .contains("oops");
        }

        @Test
        @DisplayName("a server name is never parsed as markup")
        void doesNotParseTheServerName() {
            assertThat(plain(model.custom("<server>", online, "<red>notacolour")))
                    .isEqualTo("<red>notacolour");
        }
    }

    // ------------------------------------------------------------------ sort keys

    /**
     * The tablist is ordered by the server, not by us: what actually decides the order is a scoreboard
     * team name per player, sorted alphabetically. So the key has to sort the way the groups do.
     */
    @Nested
    @DisplayName("the sort key")
    class SortKeys {

        @Test
        @DisplayName("puts the worlds in the order the groups are in")
        void sortsWorldsFirst() {
            String overworld = model.sortKey(entry(ALICE, "A", "world"));
            String nether = model.sortKey(entry(BOB, "B", "world_nether"));
            String end = model.sortKey(entry(CAROL, "C", "world_the_end"));

            assertThat(overworld).isLessThan(nether);
            assertThat(nether).isLessThan(end);
        }

        @Test
        @DisplayName("then by name inside a world")
        void sortsNamesWithinAWorld() {
            assertThat(model.sortKey(entry(ALICE, "Adam", "world")))
                    .isLessThan(model.sortKey(entry(BOB, "Zoe", "world")));
        }

        @Test
        @DisplayName("is short enough to be a team name, whatever the player is called")
        void fitsATeamName() {
            String key = model.sortKey(entry(ALICE, "A".repeat(64), "a-very-long-world-name-indeed"));
            assertThat(key.length())
                    .as("a scoreboard team name longer than this is refused by the server")
                    .isLessThanOrEqualTo(TablistModel.MAX_SORT_KEY);
        }

        @Test
        @DisplayName("is different for two different players, so neither is dropped")
        void isUniquePerPlayer() {
            assertThat(model.sortKey(entry(ALICE, "Same", "world")))
                    .isNotEqualTo(model.sortKey(entry(BOB, "Same", "world")));
        }
    }
}
