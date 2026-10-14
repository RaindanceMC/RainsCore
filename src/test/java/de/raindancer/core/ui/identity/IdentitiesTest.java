package de.raindancer.core.ui.identity;

import de.raindancer.core.testkit.TestDatabases;

import net.kyori.adventure.text.Component;
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
 * Who a player is, as everybody else sees them: their prefix, their suffix, their colour, and what
 * floats above their head.
 *
 * <h2>Why chat and the nametag are separate</h2>
 * They look the same and they are not. A chat line is a component the server builds and can make as
 * long as it likes. A nametag is rendered by the client above a moving head, has no room for a
 * sentence, and — on a vanilla client — has one line. So a player can carry a rank prefix in both,
 * a decorative suffix in chat only, and a short tag above their head, and the two are set
 * independently.
 */
@org.junit.jupiter.api.Tag("database")
class IdentitiesTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

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

    @BeforeEach
    void setUp() {
        identities = new Identities(database());
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    // ------------------------------------------------------------------ chat

    @Nested
    @DisplayName("in chat")
    class InChat {

        @Test
        @DisplayName("a plain player is just their name")
        void plainByDefault() {
            assertThat(plain(identities.chatName(ALICE, "Raindancer118"))).isEqualTo("Raindancer118");
        }

        @Test
        @DisplayName("a prefix goes in front and a suffix behind")
        void wrapsTheName() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            identities.setSuffix(ALICE, " <gray>*");

            assertThat(plain(identities.chatName(ALICE, "Raindancer118")))
                    .isEqualTo("[Admin] Raindancer118 *");
        }

        @Test
        @DisplayName("a colour recolours the name without touching the prefix")
        void coloursTheName() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            identities.setColour(ALICE, "aqua");

            Component name = identities.chatName(ALICE, "Raindancer118");
            assertThat(plain(name)).isEqualTo("[Admin] Raindancer118");
            assertThat(name.children()).isNotEmpty();
        }

        @Test
        @DisplayName("a colour accepted in capitals or with spaces is the colour it was accepted as")
        void aColourIsStoredAsAccepted() {
            assertThat(identities.setColour(ALICE, " RED ")).isTrue();

            assertThat(identities.colour(ALICE)).contains("red");
            assertThat(identities.chatName(ALICE, "Raindancer118").children().getFirst().color())
                    .as("accepted, then stored raw, it drew the name in no colour at all")
                    .isEqualTo(net.kyori.adventure.text.format.NamedTextColor.RED);
        }

        @Test
        @DisplayName("a name a player did not choose is never parsed as markup")
        void doesNotParseTheName() {
            assertThat(plain(identities.chatName(ALICE, "<red>notacolour")))
                    .as("a player called <red> is nine characters, not a colour")
                    .isEqualTo("<red>notacolour");
        }
    }

    // ------------------------------------------------------------------ nametags

    @Nested
    @DisplayName("above their head")
    class Nametag {

        @Test
        @DisplayName("uses the nametag prefix, not the chat one")
        void usesItsOwnPrefix() {
            identities.setPrefix(ALICE, "<gold>[Administrator of the realm] ");
            identities.setNametagPrefix(ALICE, "<gold>[A] ");

            assertThat(plain(identities.nametag(ALICE, "Raindancer118")))
                    .as("a chat prefix is a phrase; a nametag has room for a badge")
                    .isEqualTo("[A] Raindancer118");
        }

        @Test
        @DisplayName("falls back to the chat prefix when no nametag one is set")
        void fallsBackToTheChatPrefix() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            assertThat(plain(identities.nametag(ALICE, "Raindancer118"))).isEqualTo("[Admin] Raindancer118");
        }

        @Test
        @DisplayName("is kept short, because the client draws it over the world")
        void isClipped() {
            identities.setNametagPrefix(ALICE, "<gold>" + "x".repeat(200));
            assertThat(plain(identities.nametag(ALICE, "Raindancer118")).length())
                    .isLessThanOrEqualTo(Identities.MAX_NAMETAG_CHARS);
        }

        @Test
        @DisplayName("a second line can be set and cleared")
        void carriesASecondLine() {
            assertThat(identities.subtitle(ALICE)).isEmpty();
            identities.setSubtitle(ALICE, "<gray>0 blocks");
            assertThat(plain(identities.subtitle(ALICE).orElseThrow())).isEqualTo("0 blocks");
            identities.setSubtitle(ALICE, null);
            assertThat(identities.subtitle(ALICE)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ the tablist

    @Nested
    @DisplayName("in the tablist")
    class TablistName {

        @Test
        @DisplayName("carries both the nametag prefix and the chat prefix, nametag first")
        void carriesBoth() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            identities.setNametagPrefix(ALICE, "<gray>[AFK] ");

            assertThat(plain(identities.tablistName(ALICE, "Raindancer118")))
                    .as("a rank must not disappear the moment somebody goes AFK, and an AFK badge "
                            + "must not disappear the moment they open a menu that redraws a rank")
                    .isEqualTo("[AFK] [Admin] Raindancer118");
        }

        @Test
        @DisplayName("is their name alone when they have neither")
        void plainName() {
            assertThat(plain(identities.tablistName(ALICE, "Raindancer118")))
                    .isEqualTo("Raindancer118");
        }

        @Test
        @DisplayName("carries the suffix too")
        void carriesTheSuffix() {
            identities.setSuffix(ALICE, " <gray>*");
            assertThat(plain(identities.tablistName(ALICE, "Raindancer118")))
                    .isEqualTo("Raindancer118 *");
        }

        @Test
        @DisplayName("is not clipped the way the over-the-head nametag is")
        void isNotClippedLikeTheNametag() {
            identities.setNametagPrefix(ALICE, "<gray>" + "x".repeat(100));
            assertThat(plain(identities.tablistName(ALICE, "Raindancer118")).length())
                    .isGreaterThan(Identities.MAX_NAMETAG_CHARS);
        }
    }

    // ------------------------------------------------------------------ safety

    @Nested
    @DisplayName("what a prefix may contain")
    class Safety {

        @Test
        @DisplayName("markup is allowed, because that is the point of a prefix")
        void allowsColours() {
            assertThat(identities.setPrefix(ALICE, "<gradient:gold:yellow>[VIP]</gradient> ")).isTrue();
        }

        /**
         * MiniMessage does not throw on a tag it has never heard of — it renders it as text. So a
         * prefix of {@code <notatag>[Oops] } would be stored happily and then appear, literally,
         * in front of that player's name for ever. Whoever typed it should be told now.
         */
        @Test
        @DisplayName("a tag MiniMessage does not know is refused, not rendered as text")
        void refusesUnknownTags() {
            assertThat(identities.setPrefix(ALICE, "<notatag>[Oops] ")).isFalse();
            assertThat(identities.prefix(ALICE)).isEmpty();
        }

        @Test
        @DisplayName("an unclosed colour is fine, because that is how prefixes are written")
        void allowsUnclosedColours() {
            assertThat(identities.setPrefix(ALICE, "<gold>[Admin] ")).isTrue();
        }

        @Test
        @DisplayName("the space at the end of a prefix is kept, because it does the separating")
        void keepsTrailingSpace() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            assertThat(plain(identities.chatName(ALICE, "Raindancer118")))
                    .as("trimming it glues every rank to every name")
                    .isEqualTo("[Admin] Raindancer118");
        }

        @Test
        @DisplayName("an absurdly long prefix is refused")
        void refusesOverlongPrefixes() {
            assertThat(identities.setPrefix(ALICE, "x".repeat(500))).isFalse();
        }

        @Test
        @DisplayName("clearing sets it back to nothing")
        void clears() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            identities.setPrefix(ALICE, null);
            assertThat(identities.prefix(ALICE)).isEmpty();
        }

        @Test
        @DisplayName("nulls everywhere are survivable")
        void survivesNulls() {
            assertThatCode(() -> {
                identities.setPrefix(null, "x");
                identities.setColour(ALICE, null);
                identities.chatName(null, null);
                identities.nametag(null, null);
            }).doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------ persistence

    @Nested
    @DisplayName("across a restart")
    class Persistence {

        @Test
        @DisplayName("everything a player was given is still theirs")
        void roundTrips() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            identities.setSuffix(ALICE, " <gray>*");
            identities.setNametagPrefix(ALICE, "<gold>[A] ");
            identities.setColour(ALICE, "aqua");
            identities.setSubtitle(ALICE, "<gray>0 blocks");
            identities.setPrefix(BOB, "<green>[Member] ");
            identities.flush();

            // Closed and reopened over the same file, because that is what a restart is: reusing the
            // open connection would prove only that the in-memory copy is still there.
            openedDatabase.close();
            Identities reopened = new Identities(database());
            reopened.load();

            assertThat(plain(reopened.chatName(ALICE, "Raindancer118"))).isEqualTo("[Admin] Raindancer118 *");
            assertThat(plain(reopened.nametag(ALICE, "Raindancer118"))).isEqualTo("[A] Raindancer118");
            assertThat(reopened.colour(ALICE)).contains("aqua");
            assertThat(plain(reopened.subtitle(ALICE).orElseThrow())).isEqualTo("0 blocks");
            assertThat(plain(reopened.chatName(BOB, "Bentex_OG"))).isEqualTo("[Member] Bentex_OG");
        }

        @Test
        @DisplayName("a player with nothing set is not written at all")
        void doesNotStoreEmptyPlayers() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            identities.setPrefix(ALICE, null);
            identities.flush();

            openedDatabase.close();
            Identities reopened = new Identities(database());
            reopened.load();
            assertThat(reopened.known()).isEmpty();
        }

        @Test
        @DisplayName("a database with nothing in it is an empty set of identities, not a failure")
        void survivesAMissingFile() {
            Identities fresh = new Identities(TestDatabases.open(
                    directory.resolve("never-used.db"), CoreSchema.CORE, () -> false));
            assertThatCode(fresh::load).doesNotThrowAnyException();
            assertThat(fresh.known()).isEmpty();
        }

        @Test
        @DisplayName("nothing is written when nothing changed")
        void doesNotWriteWithoutChanges() {
            identities.load();
            identities.flush();
            assertThat(directory.resolve("identities.yml")).doesNotExist();
        }
    }

    // ------------------------------------------------------------------ symbols

    /**
     * The symbol set: the handful of characters these plugins use for the same things everywhere, so
     * a tick means the same in a menu, in chat and on a sign.
     */
    @Nested
    @DisplayName("the symbol set")
    class TheSymbolSet {

        @Test
        @DisplayName("every symbol has a name that can be looked up")
        void looksUpByName() {
            assertThat(Symbols.of("tick")).isEqualTo(Symbols.TICK);
            assertThat(Symbols.of("TICK")).isEqualTo(Symbols.TICK);
            assertThat(Symbols.of("nothing-like-this")).isEmpty();
        }

        @Test
        @DisplayName("the names are listed, so a command can complete them")
        void listsItsNames() {
            assertThat(Symbols.names()).contains("tick", "cross", "arrow", "star");
        }

        @Test
        @DisplayName("a symbol is one character, so it fits where a character fits")
        void areSingleCharacters() {
            for (String name : Symbols.names()) {
                assertThat(Symbols.of(name))
                        .as("%s", name)
                        .hasSize(1);
            }
        }

        @Test
        @DisplayName("placeholders in a line are replaced")
        void replacesPlaceholders() {
            assertThat(Symbols.expand("Done :tick: and not :cross:"))
                    .isEqualTo("Done " + Symbols.TICK + " and not " + Symbols.CROSS);
        }

        @Test
        @DisplayName("an unknown placeholder is left alone rather than blanked")
        void leavesUnknownPlaceholders() {
            assertThat(Symbols.expand("what is :nonsense: here"))
                    .isEqualTo("what is :nonsense: here");
        }

        @Test
        @DisplayName("text with no placeholders comes back untouched")
        void leavesPlainTextAlone() {
            assertThat(Symbols.expand("nothing to do")).isEqualTo("nothing to do");
            assertThat(Symbols.expand(null)).isEmpty();
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a prefix that would be a button is refused; one that is only colour is kept")
    void noButtonsInNames() {
        UUID somebody = UUID.randomUUID();
        assertThat(identities.setPrefix(somebody, "<click:run_command:'/op Steve'>[VIP] ")).isFalse();
        assertThat(identities.setPrefix(somebody, "<hover:show_text:'x'>[VIP] ")).isFalse();
        assertThat(identities.setPrefix(somebody, "<gold>[VIP] ")).isTrue();
    }

    // ------------------------------------------------------------------ styled names

    @Nested
    @DisplayName("a name style — gradients and decorations")
    class Styles {

        private final net.kyori.adventure.text.format.TextColor red =
                net.kyori.adventure.text.format.NamedTextColor.RED;
        private final net.kyori.adventure.text.format.TextColor blue =
                net.kyori.adventure.text.format.NamedTextColor.BLUE;

        private de.raindancer.core.ui.text.NameStyle gradient() {
            return de.raindancer.core.ui.text.NameStyle.NONE.withStop(red).withStop(blue)
                    .with(net.kyori.adventure.text.format.TextDecoration.BOLD, true);
        }

        /** Every coloured piece of the name, left to right. */
        private List<net.kyori.adventure.text.format.TextColor> colours(Component component) {
            List<net.kyori.adventure.text.format.TextColor> found = new java.util.ArrayList<>();
            walk(component, found);
            return found;
        }

        private void walk(Component component, List<net.kyori.adventure.text.format.TextColor> found) {
            if (component instanceof net.kyori.adventure.text.TextComponent text
                    && !text.content().isEmpty() && text.color() != null) {
                found.add(text.color());
            }
            component.children().forEach(child -> walk(child, found));
        }

        @Test
        @DisplayName("a gradient paints the name from its first stop to its last, in chat and in the tablist")
        void gradientIsPainted() {
            assertThat(identities.setNameStyle(ALICE, gradient())).isTrue();

            Component chat = identities.chatName(ALICE, "Raindancer118");
            assertThat(plain(chat)).isEqualTo("Raindancer118");
            assertThat(colours(chat)).hasSize("Raindancer118".length());
            assertThat(colours(chat).getFirst()).isEqualTo(red);
            assertThat(colours(chat).getLast()).isEqualTo(blue);

            assertThat(colours(identities.tablistName(ALICE, "Raindancer118")).getLast()).isEqualTo(blue);
            assertThat(colours(identities.nametag(ALICE, "Raindancer118")).getFirst()).isEqualTo(red);
        }

        @Test
        @DisplayName("the decorations are on the name and nowhere else")
        void decorationsStayOnTheName() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            identities.setNameStyle(ALICE, gradient());

            Component chat = identities.chatName(ALICE, "Raindancer118");
            assertThat(plain(chat)).isEqualTo("[Admin] Raindancer118");
            Component prefix = chat.children().getFirst();
            assertThat(prefix.decoration(net.kyori.adventure.text.format.TextDecoration.BOLD))
                    .as("a bold name must not make the rank bold")
                    .isNotEqualTo(net.kyori.adventure.text.format.TextDecoration.State.TRUE);
            Component name = chat.children().get(1);
            assertThat(name.decoration(net.kyori.adventure.text.format.TextDecoration.BOLD))
                    .isEqualTo(net.kyori.adventure.text.format.TextDecoration.State.TRUE);
        }

        @Test
        @DisplayName("a name is still text, never markup, however it is painted")
        void theNameIsNeverParsed() {
            identities.setNameStyle(ALICE, gradient());
            assertThat(plain(identities.chatName(ALICE, "<red>x"))).isEqualTo("<red>x");
        }

        @Test
        @DisplayName("the style is read back as it was set, and a plain colour reads as a one-stop style")
        void readsBack() {
            identities.setNameStyle(ALICE, gradient());
            assertThat(identities.nameStyle(ALICE)).isEqualTo(gradient());

            identities.setColour(BOB, "aqua");
            assertThat(identities.nameStyle(BOB).colours())
                    .containsExactly(net.kyori.adventure.text.format.NamedTextColor.AQUA);

            assertThat(identities.nameStyle(UUID.randomUUID()))
                    .isEqualTo(de.raindancer.core.ui.text.NameStyle.NONE);
        }

        @Test
        @DisplayName("an empty style clears the colour, and leaves the prefix alone")
        void emptyClears() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            identities.setNameStyle(ALICE, gradient());
            identities.setNameStyle(ALICE, de.raindancer.core.ui.text.NameStyle.NONE);

            assertThat(identities.colour(ALICE)).isEmpty();
            assertThat(identities.prefix(ALICE)).contains("<gold>[Admin] ");
            assertThat(identities.nameStyle(ALICE)).isEqualTo(de.raindancer.core.ui.text.NameStyle.NONE);
        }

        @Test
        @DisplayName("more stops than a name can carry are refused, not cut short")
        void tooManyStopsAreRefused() {
            de.raindancer.core.ui.text.NameStyle style = de.raindancer.core.ui.text.NameStyle.NONE;
            for (int stop = 0; stop <= de.raindancer.core.ui.text.NameStyle.MAX_STOPS; stop++) {
                style = style.withStop(stop % 2 == 0 ? red : blue);
            }
            assertThat(identities.setNameStyle(ALICE, style)).isFalse();
            assertThat(identities.nameStyle(ALICE)).isEqualTo(de.raindancer.core.ui.text.NameStyle.NONE);
            assertThat(identities.setNameStyle(null, gradient())).isFalse();
        }

        @Test
        @DisplayName("a gradient survives a restart")
        void survivesARestart() {
            identities.setNameStyle(ALICE, gradient());
            identities.flush();

            openedDatabase.close();
            Identities reopened = new Identities(database());
            reopened.load();

            assertThat(reopened.nameStyle(ALICE)).isEqualTo(gradient());
        }
    }

    // ------------------------------------------------------------------ nicknames

    @Nested
    @DisplayName("a nickname, as essentials hands it over")
    class Nicknames {

        @Test
        @DisplayName("is shown in chat, in the tablist and on the nametag instead of the real name")
        void shownEverywhere() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            identities.setNickname(ALICE, "Rain");

            assertThat(plain(identities.chatName(ALICE, "Raindancer118"))).isEqualTo("[Admin] Rain");
            assertThat(plain(identities.tablistName(ALICE, "Raindancer118"))).isEqualTo("[Admin] Rain");
            assertThat(plain(identities.nametag(ALICE, "Raindancer118"))).isEqualTo("[Admin] Rain");
            assertThat(identities.shownName(ALICE, "Raindancer118")).isEqualTo("Rain");
        }

        @Test
        @DisplayName("takes the name style like the real name would")
        void styled() {
            identities.setNameStyle(ALICE, de.raindancer.core.ui.text.NameStyle.NONE
                    .withStop(net.kyori.adventure.text.format.NamedTextColor.RED)
                    .withStop(net.kyori.adventure.text.format.NamedTextColor.BLUE));
            identities.setNickname(ALICE, "Rain");
            assertThat(identities.chatName(ALICE, "Raindancer118").children().getFirst().children())
                    .hasSize(4);
        }

        @Test
        @DisplayName("is text, never markup, and clearing it brings the real name back")
        void plainAndClearable() {
            identities.setNickname(ALICE, "<red>Rain");
            assertThat(plain(identities.chatName(ALICE, "Raindancer118"))).isEqualTo("<red>Rain");
            identities.setNickname(ALICE, null);
            assertThat(plain(identities.chatName(ALICE, "Raindancer118"))).isEqualTo("Raindancer118");
            identities.setNickname(ALICE, "   ");
            assertThat(identities.shownName(ALICE, "Raindancer118")).isEqualTo("Raindancer118");
        }
    }

    @Nested
    @DisplayName("an animated name")
    class Animated {

        @Test
        @DisplayName("is painted differently as time passes, and the same at the same moment")
        void movesWithTheClock() {
            long[] now = {0};
            Identities clocked = new Identities(database(), () -> now[0]);
            clocked.setNameStyle(ALICE, de.raindancer.core.ui.text.NameStyle.NONE
                    .withStop(net.kyori.adventure.text.format.NamedTextColor.RED)
                    .withStop(net.kyori.adventure.text.format.NamedTextColor.BLUE).animated(true));

            Component first = clocked.chatName(ALICE, "Raindancer118");
            assertThat(clocked.chatName(ALICE, "Raindancer118")).isEqualTo(first);
            now[0] = Identities.ANIMATION_PERIOD_MS / 4;
            assertThat(clocked.chatName(ALICE, "Raindancer118")).isNotEqualTo(first);
            assertThat(clocked.isAnimated(ALICE)).isTrue();
            assertThat(clocked.isAnimated(BOB)).isFalse();
        }
    }

    @Test
    @DisplayName("painted: any text in a player's own name style, plain when they have none")
    void painted() {
        Identities fresh = new Identities(database());
        assertThat(fresh.hasNameStyle(ALICE)).isFalse();
        assertThat(PlainTextComponentSerializer.plainText().serialize(fresh.painted(ALICE, "@Alice")))
                .isEqualTo("@Alice");
        assertThat(fresh.painted(ALICE, "@Alice").color()).isNull();
    }
}
