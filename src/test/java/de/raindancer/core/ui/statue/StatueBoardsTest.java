package de.raindancer.core.ui.statue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatueBoardsTest {

    private static final UUID ADA = UUID.randomUUID();
    private static final UUID BO = UUID.randomUUID();

    @AfterEach
    void clear() {
        StatueBoards.clear();
    }

    private static StatueBoard richest(List<StatueBoard.Standing> standings) {
        return StatueBoard.ranking("richest", "<gold>Richest players", "<gray>", "richest players", () -> standings);
    }

    private static String plain(Component text) {
        return PlainTextComponentSerializer.plainText().serialize(text);
    }

    @Test
    @DisplayName("a board's id is what staff type: lower case, no spaces, no dots")
    void ids() {
        assertThatThrownBy(() -> StatueBoard.ranking("Rich est", "h", "", "x", List::of))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatueBoard.ranking("economy:richest", "h", "", "x", List::of))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatueBoard.ranking("richest", "h", "", "x", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(StatueBoard.thanks("creator", "<light_purple>Our creators", "<gray>Making videos",
                "<light_purple>CREATOR", "creators", List::of).thankYou()).isTrue();
    }

    @Test
    @DisplayName("a ranking's statue is named with its medal colour, an empty place says so, names cannot inject tags")
    void rankingTitles() {
        StatueBoard board = richest(List.of());
        assertThat(board.title(1, "Ada")).isEqualTo("<gold><bold>#1</bold> <white>Ada");
        assertThat(board.title(3, "Cy")).startsWith("<#cd7f32>");
        assertThat(board.title(2, null)).isEqualTo("<gray><bold>#2</bold> <dark_gray>Nobody yet");
        assertThat(board.title(1, "<red>x")).endsWith("\\<red>x");
    }

    @Test
    @DisplayName("a thank-you statue carries the board's mark and no place; a free one is waiting")
    void thankYouTitles() {
        StatueBoard prime = StatueBoard.thanks("prime", "Our Prime supporters", "<gray>Thank you",
                "<light_purple>PRIME", "Prime supporters", List::of);
        assertThat(prime.title(9, "Ada")).contains("PRIME").contains("Ada").doesNotContain("#9");
        assertThat(prime.title(9, null)).contains("PRIME").contains("waiting");
    }

    @Test
    @DisplayName("the name over a statue is drawn the way the player's name is, behind the medal or the mark")
    void paintedNames() {
        StatueBoard.Look look = StatueBoard.Look.of(
                (who, name) -> Component.text(name, net.kyori.adventure.text.format.TextColor.color(0x7DD3FC)),
                tag -> java.util.Optional.empty());
        Component ranked = richest(List.of()).nameplate(1, new StatueBoard.Standing(ADA, "Ada", null), look);
        assertThat(plain(ranked)).isEqualTo("#1 Ada");
        assertThat(ranked.children().getLast().color()).isEqualTo(net.kyori.adventure.text.format.TextColor.color(0x7DD3FC));

        StatueBoard creators = StatueBoard.thanks("creator", "Our creators", "", "<light_purple>[CREATOR]", "creators",
                List::of);
        assertThat(plain(creators.nameplate(4, new StatueBoard.Standing(ADA, "Ada", null), look)))
                .isEqualTo("[CREATOR] Ada");
        assertThat(plain(creators.nameplate(4, null, look))).contains("waiting");
        assertThat(plain(richest(List.of()).nameplate(1, new StatueBoard.Standing(ADA, "Ada", null),
                StatueBoard.Look.PLAIN))).isEqualTo("#1 Ada");
    }

    @Test
    @DisplayName("a thank-you mark is the rank's badge where badges show; a standing may bring a badge of its own")
    void badges() {
        Component badge = Component.text("\uF900");
        StatueBoard.Look look = StatueBoard.Look.of((who, name) -> Component.text(name),
                tag -> tag.contains("CREATOR") ? java.util.Optional.of(badge) : java.util.Optional.empty());
        StatueBoard creators = StatueBoard.thanks("creator", "Our creators", "", "<light_purple>[CREATOR]", "creators",
                List::of);
        assertThat(plain(creators.nameplate(1, new StatueBoard.Standing(ADA, "Ada", null), look)))
                .isEqualTo("\uF900 Ada");
        assertThat(plain(creators.nameplate(1, null, look))).startsWith("\uF900 ");

        StatueBoard team = StatueBoard.thanks("team", "Our team", "", "[TEAM]", "team", List::of);
        Component own = Component.text("\uF901");
        assertThat(plain(team.nameplate(1, new StatueBoard.Standing(ADA, "Ada", null, own), look)))
                .isEqualTo("\uF901 Ada");
    }

    @Test
    @DisplayName("a board without a caption puts no line under the name at all, not an empty one")
    void noEmptyLine() {
        StatueBoard creators = StatueBoard.thanks("creator", "Our creators", "", "CREATOR", "creators", List::of);
        assertThat(creators.subtitle(new StatueBoard.Standing(ADA, "Ada", null))).isEmpty();
        assertThat(creators.subtitle(null)).isEmpty();
        StatueBoard casino = StatueBoard.ranking("casino", "h", "<gray>won ", "casino winners", List::of);
        assertThat(casino.subtitle(new StatueBoard.Standing(ADA, "Ada", Component.text("5 coins"))).map(this::plainOf))
                .contains("won 5 coins");
    }

    private String plainOf(Component text) {
        return plain(text);
    }

    @Test
    @DisplayName("a reload asks every board that can look again to do so; one that fails does not stop the others")
    void lookAgain() {
        java.util.concurrent.atomic.AtomicInteger looked = new java.util.concurrent.atomic.AtomicInteger();
        StatueBoard creators = StatueBoard.thanks("creator", "h", "", "CREATOR", "creators", List::of);
        StatueBoard team = StatueBoard.thanks("team", "h", "", "TEAM", "team", List::of);
        StatueBoards.offer(getClass().getClassLoader(), creators, () -> {
            throw new IllegalStateException("storage gone");
        });
        StatueBoards.offer(getClass().getClassLoader(), team, looked::incrementAndGet);
        StatueBoards.offer(getClass().getClassLoader(), richest(List.of()));

        assertThat(StatueBoards.lookAgain()).isEqualTo(1);
        assertThat(looked).hasValue(1);
        StatueBoards.withdraw(team);
        StatueBoards.lookAgain();
        assertThat(looked).as("a withdrawn board is not asked").hasValue(1);
    }

    @Test
    @DisplayName("the line under a name is the caption and what the place is held with")
    void line() {
        StatueBoard board = StatueBoard.ranking("casino", "Biggest casino winners", "<gray>won ", "casino winners",
                List::of);
        assertThat(plain(board.line(new StatueBoard.Standing(ADA, "Ada", Component.text("5,000 coins")))))
                .isEqualTo("won 5,000 coins");
        assertThat(plain(board.line(new StatueBoard.Standing(ADA, "Ada", null)))).isEqualTo("won ");
        assertThat(plain(board.line(null))).isEmpty();
    }

    @Test
    @DisplayName("an offered board is found by its id; the newest offer of an id wins; withdrawing an old one leaves the new")
    void offerAndWithdraw() {
        StatueBoard first = richest(List.of(new StatueBoard.Standing(ADA, "Ada", null)));
        StatueBoard second = richest(List.of(new StatueBoard.Standing(BO, "Bo", null)));
        StatueBoards.offer(getClass().getClassLoader(), first);
        assertThat(StatueBoards.find("richest")).containsSame(first);
        StatueBoards.offer(getClass().getClassLoader(), second);
        assertThat(StatueBoards.find("richest")).containsSame(second);
        StatueBoards.withdraw(first);
        assertThat(StatueBoards.find("richest")).containsSame(second);
        assertThat(StatueBoards.ids()).containsExactly("richest");
        StatueBoards.withdraw(second);
        assertThat(StatueBoards.find("richest")).isEmpty();
        assertThat(StatueBoards.find(null)).isEmpty();
        assertThat(StatueBoards.find("RICHEST")).isEmpty();
    }

    @Test
    @DisplayName("a board that throws or answers null reads as nobody, so its statues stay as they are")
    void failSafe() {
        StatueBoards.offer(getClass().getClassLoader(), StatueBoard.ranking("broken", "h", "", "x", () -> {
            throw new IllegalStateException("database gone");
        }));
        StatueBoards.offer(getClass().getClassLoader(), StatueBoard.ranking("empty", "h", "", "x", () -> null));
        assertThat(StatueBoards.standings("broken")).isEmpty();
        assertThat(StatueBoards.standings("empty")).isEmpty();
        assertThat(StatueBoards.standings("nobody")).isEmpty();
    }

    @Test
    @DisplayName("a plugin going away takes its boards along, nobody else's")
    void forgetFrom() throws Exception {
        try (URLClassLoader other = new URLClassLoader(new URL[0], getClass().getClassLoader())) {
            StatueBoards.offer(other, richest(List.of()));
            StatueBoards.offer(getClass().getClassLoader(), StatueBoard.thanks("prime", "h", "", "PRIME", "Prime",
                    List::of));
            assertThat(StatueBoards.forgetFrom(other)).isEqualTo(1);
            assertThat(StatueBoards.ids()).containsExactly("prime");
        }
    }

    @Test
    @DisplayName("people by id become standings with their names, in their order; somebody never seen is left out")
    void named() {
        UUID ghost = UUID.randomUUID();
        Map<UUID, String> names = Map.of(ADA, "Ada", BO, "Bo");
        List<StatueBoard.Standing> standings = StatueBoards.named(List.of(BO, ghost, ADA),
                id -> Optional.ofNullable(names.get(id)));
        assertThat(standings).extracting(StatueBoard.Standing::name).containsExactly("Bo", "Ada");
        assertThat(standings).extracting(StatueBoard.Standing::player).containsExactly(BO, ADA);
    }
}
