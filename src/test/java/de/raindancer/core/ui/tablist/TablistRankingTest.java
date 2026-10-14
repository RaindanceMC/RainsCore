package de.raindancer.core.ui.tablist;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.ui.identity.Identities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Nested;
import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Putting the people who matter at the top, and making the header move.
 *
 * <h2>Where this came from</h2>
 * Velocitab does both on a proxy, and both are the same two ideas: the list's order is not the
 * server's to set directly — Minecraft sorts it by scoreboard team name, alphabetically, and that is
 * the only lever there is — and a header that never changes is a header nobody reads twice.
 *
 * <p>We already sorted by world and then by name, using exactly that lever. What was missing is the
 * thing anybody actually wants from it: staff at the top, then whoever else, rather than the admin
 * being wherever the alphabet put them.
 */
@org.junit.jupiter.api.Tag("database")
@DisplayName("tablist ranking")
class TablistRankingTest {

    @TempDir
    Path folder;

    private TablistModel model() {
        return new TablistModel(new Identities(TestDatabases.open(
                folder.resolve("identities.db"), CoreSchema.CORE, () -> false)));
    }

    private static TablistEntry player(String name, String world) {
        return new TablistEntry(UUID.nameUUIDFromBytes(name.getBytes()), name, world, 40);
    }

    private static final TablistEntry ADMIN = player("Zoe", "world");
    private static final TablistEntry MOD = player("Yannick", "world");
    private static final TablistEntry PLAYER = player("Alice", "world");

    // ------------------------------------------------------------------ weight

    @Nested
    @DisplayName("sorting by rank")
    class Ranking {

        @Test
        @DisplayName("without ranks it is still world and then name")
        void unrankedIsUnchanged() {
            TablistModel model = model();
            List<TablistGroup> groups = model.groups(List.of(ADMIN, PLAYER));

            assertThat(groups.getFirst().entries())
                    .extracting(TablistEntry::name)
                    .as("a server that has not set any ranks must be no worse off than before")
                    .containsExactly("Alice", "Zoe");
        }

        @Test
        @DisplayName("a heavier rank sorts above a lighter one, whatever the alphabet says")
        void weightBeatsTheAlphabet() {
            TablistModel model = model();
            model.rankOf(entry -> switch (entry.name()) {
                case "Zoe" -> 100;
                case "Yannick" -> 50;
                default -> 0;
            });

            assertThat(model.groups(List.of(PLAYER, MOD, ADMIN)).getFirst().entries())
                    .extracting(TablistEntry::name)
                    .as("the whole point: the admin at the top, not wherever Z falls")
                    .containsExactly("Zoe", "Yannick", "Alice");
        }

        @Test
        @DisplayName("two people of the same rank are still ordered by name")
        void tiesFallBackToName() {
            TablistModel model = model();
            model.rankOf(entry -> 10);

            assertThat(model.groups(List.of(player("Bob", "world"), player("Alice", "world")))
                    .getFirst().entries())
                    .extracting(TablistEntry::name)
                    .as("a stable order matters: a list that reshuffles every refresh is unusable")
                    .containsExactly("Alice", "Bob");
        }

        @Test
        @DisplayName("the sort key carries the rank, because the client does the sorting")
        void theKeyCarriesIt() {
            TablistModel model = model();
            model.rankOf(entry -> entry.name().equals("Zoe") ? 100 : 0);

            assertThat(model.sortKey(ADMIN))
                    .as("Minecraft sorts by team name alphabetically and nothing else; if the rank "
                            + "is not in the key it does not exist as far as the client is concerned")
                    .isLessThan(model.sortKey(PLAYER));
        }

        @Test
        @DisplayName("the key is still short enough for the game to accept")
        void keysStayShort() {
            TablistModel model = model();
            model.rankOf(entry -> 999);

            assertThat(model.sortKey(player("Somebody_With_A_Very_Long_Name", "world")).length())
                    .as("a team name over the limit is silently refused, which shows up as one "
                            + "player sorted wrongly and nothing in the log")
                    .isLessThanOrEqualTo(TablistModel.MAX_SORT_KEY);
        }

        @Test
        @DisplayName("world grouping still wins over rank, because the groups are headings")
        void worldStillGroups() {
            TablistModel model = model();
            model.rankOf(entry -> entry.name().equals("Zoe") ? 100 : 0);

            List<TablistGroup> groups = model.groups(List.of(player("Alice", "world"),
                    new TablistEntry(ADMIN.player(), "Zoe", "world_nether", 40)));

            assertThat(groups)
                    .as("an admin in the nether belongs under the nether heading, not floating "
                            + "above the overworld one")
                    .hasSize(2);
            assertThat(groups.getFirst().world()).isEqualTo("world");
        }

        @Test
        @DisplayName("a rank function that throws does not take the tablist with it")
        void survivesABadRankFunction() {
            TablistModel model = model();
            model.rankOf(entry -> {
                throw new IllegalStateException("the permissions plugin is not loaded yet");
            });

            assertThat(model.groups(List.of(ADMIN, PLAYER)))
                    .as("a tablist that vanishes because somebody's rank lookup failed is worse "
                            + "than one in the wrong order")
                    .isNotEmpty();
        }
    }

    // ------------------------------------------------------------------ animation

    @Nested
    @DisplayName("a header that moves")
    class Animation {

        @Test
        @DisplayName("one frame is the same every time, which is what a plain header is")
        void oneFrame() {
            Animated header = Animated.of("<gold>My Server");
            assertThat(header.frameAt(0)).isEqualTo("<gold>My Server");
            assertThat(header.frameAt(99)).isEqualTo("<gold>My Server");
        }

        @Test
        @DisplayName("several frames cycle in order and wrap round")
        void cycles() {
            Animated header = Animated.of(List.of("one", "two", "three"));
            assertThat(header.frameAt(0)).isEqualTo("one");
            assertThat(header.frameAt(1)).isEqualTo("two");
            assertThat(header.frameAt(2)).isEqualTo("three");
            assertThat(header.frameAt(3))
                    .as("wrapping is the whole idea of an animation")
                    .isEqualTo("one");
        }

        @Test
        @DisplayName("it can be slowed down, so a frame lasts several refreshes")
        void everyNthTick() {
            Animated header = Animated.of(List.of("one", "two")).everyTicks(3);
            assertThat(header.frameAt(0)).isEqualTo("one");
            assertThat(header.frameAt(2)).isEqualTo("one");
            assertThat(header.frameAt(3))
                    .as("the tablist refreshes twice a second; a frame per refresh is a strobe")
                    .isEqualTo("two");
        }

        @Test
        @DisplayName("a negative tick does not throw or run backwards off the end")
        void oddTicks() {
            Animated header = Animated.of(List.of("one", "two"));
            assertThat(header.frameAt(-1)).isNotNull();
            assertThat(header.frameAt(Integer.MIN_VALUE)).isNotNull();
        }

        @Test
        @DisplayName("no frames at all is empty rather than a crash")
        void noFrames() {
            assertThat(Animated.of(List.of()).frameAt(0)).isEmpty();
            assertThat(Animated.of((String) null).frameAt(0)).isEmpty();
        }

        @Test
        @DisplayName("it says whether it actually moves, so nothing redraws for nothing")
        void knowsWhetherItMoves() {
            assertThat(Animated.of("still").isAnimated()).isFalse();
            assertThat(Animated.of(List.of("a", "b")).isAnimated()).isTrue();
        }
    }

    // ------------------------------------------------------------------ the ping

    /**
     * Showing the latency as a number.
     *
     * <p>The five-bar icon at the right-hand end is drawn by the client from the latency the server
     * sends, and there is no packet that removes it. What can be done is put the real number on the
     * line, which is what anybody wanting it actually wants — "is it me or the server" is not a
     * question five bars can answer.
     */
    @Nested
    @DisplayName("the ping as a number")
    class Ping {

        @Test
        @DisplayName("it is off unless asked for")
        void offByDefault() {
            assertThat(plain(model().line(PLAYER)))
                    .as("a number after every name is clutter on a server nobody asked")
                    .doesNotContain("ms");
        }

        @Test
        @DisplayName("with it on, the number is on the line")
        void showsTheNumber() {
            TablistModel model = model();
            model.showPing(true);

            assertThat(plain(model.line(new TablistEntry(UUID.randomUUID(), "Alice", "world", 42))))
                    .contains("42");
        }

        @Test
        @DisplayName("it is coloured by how bad it is, so the list can be read at a glance")
        void coloursByQuality() {
            assertThat(TablistModel.pingColour(30)).isNotEqualTo(TablistModel.pingColour(400));
            assertThat(TablistModel.pingColour(30)).isEqualTo(TablistModel.pingColour(40));
        }

        @Test
        @DisplayName("a latency the server does not know yet is not shown as zero")
        void unknownPing() {
            TablistModel model = model();
            model.showPing(true);

            assertThat(plain(model.line(new TablistEntry(UUID.randomUUID(), "Alice", "world", -1))))
                    .as("'0ms' for somebody who has just joined is a number that is simply wrong")
                    .doesNotContain("0ms");
        }

        @Test
        @DisplayName("it works alongside the world label")
        void withTheWorld() {
            TablistModel model = model();
            model.showPing(true);

            String line = plain(model.lineWithWorld(
                    new TablistEntry(UUID.randomUUID(), "Alice", "world", 42)));
            assertThat(line).contains("42").contains("Overworld");
        }
    }

    // ------------------------------------------------------------------ the title and the logo

    /**
     * What sits at the very top.
     *
     * <p>The header is the largest piece of text a server ever puts in front of a player, and by
     * default it is the MOTD in a gradient — which is fine and says nothing. A title an owner chose,
     * and a few lines of glyphs above it, is the one place a server gets to look like itself.
     */
    @Nested
    @DisplayName("the title and the logo")
    class Branding {

        @Test
        @DisplayName("without a title it is the server name, as it was")
        void defaultsToTheServerName() {
            assertThat(plain(model().header(List.of(PLAYER), "My Server")))
                    .as("a server that sets nothing must be no worse off than before")
                    .contains("My Server");
        }

        @Test
        @DisplayName("a title replaces it")
        void titleWins() {
            TablistModel model = model();
            model.title("<gold>RAINDANCER</gold>");

            String header = plain(model.header(List.of(PLAYER), "My Server"));
            assertThat(header).contains("RAINDANCER").doesNotContain("My Server");
        }

        @Test
        @DisplayName("a logo sits above the title, line by line")
        void logoAboveTheTitle() {
            TablistModel model = model();
            model.title("<gold>RAIN");
            model.logo(List.of("<gray>▛▀▖", "<gray>▙▄▘"));

            String header = plain(model.header(List.of(PLAYER), "My Server"));
            assertThat(header.indexOf("▛"))
                    .as("a logo underneath the name is a footer, not a logo")
                    .isLessThan(header.indexOf("RAIN"));
            assertThat(header).contains("▙▄▘");
        }

        @Test
        @DisplayName("the player count stays, because it is the one thing people look for")
        void countSurvives() {
            TablistModel model = model();
            model.title("<gold>RAIN");
            model.logo(List.of("<gray>▛▀▖"));

            assertThat(plain(model.header(List.of(PLAYER, MOD), "My Server")))
                    .contains("2 players online");
        }

        @Test
        @DisplayName("a title with a typo in its markup still shows the words")
        void brokenTitleMarkup() {
            TablistModel model = model();
            model.title("<not_a_colour>RAIN");

            assertThat(plain(model.header(List.of(PLAYER), "My Server")))
                    .as("a broken header must not empty the whole tablist")
                    .contains("RAIN");
        }

        @Test
        @DisplayName("no logo and no title is simply neither")
        void emptyBranding() {
            TablistModel model = model();
            model.title("");
            model.logo(List.of());

            assertThat(plain(model.header(List.of(PLAYER), "My Server"))).contains("My Server");
        }

        @Test
        @DisplayName("a logo can be built out of a name, so nobody has to draw one")
        void logoFromAName() {
            List<String> drawn = TablistModel.logoFor("RC");

            assertThat(drawn)
                    .as("an owner who wants a logo and cannot draw one should still get one")
                    .isNotEmpty();
            assertThat(String.join("", drawn)).isNotBlank();
        }
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(component);
    }
}
