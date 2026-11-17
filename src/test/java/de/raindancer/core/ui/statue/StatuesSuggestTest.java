package de.raindancer.core.ui.statue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatuesSuggestTest {

    @BeforeEach
    void offer() {
        StatueBoards.offer(getClass().getClassLoader(), StatueBoard.ranking("richest", "h", "", "richest players",
                List::of));
        StatueBoards.offer(getClass().getClassLoader(), StatueBoard.ranking("casino", "h", "", "casino winners",
                List::of));
        StatueBoards.offer(getClass().getClassLoader(), StatueBoard.thanks("prime", "h", "", "PRIME",
                "Prime supporters", List::of));
    }

    @AfterEach
    void clear() {
        StatueBoards.clear();
    }

    @Test
    @DisplayName("the command offers placing, moving and removing, then every offered board, then a ranking's places")
    void suggest() {
        assertThat(Statues.suggest(new String[]{""})).containsExactly("place", "move", "remove");
        assertThat(Statues.suggest(new String[]{"place", ""})).containsExactly("casino", "prime", "richest");
        assertThat(Statues.suggest(new String[]{"move", "ca"})).containsExactly("casino");
        assertThat(Statues.suggest(new String[]{"place", "richest", ""})).containsExactly("1", "2", "3");
        assertThat(Statues.suggest(new String[]{"place", "prime", ""})).as("a thank-you is placed one by one")
                .isEmpty();
        assertThat(Statues.suggest(new String[]{"place", "jackpot", ""})).isEmpty();
        assertThat(Statues.suggest(new String[]{"remove", ""})).containsExactly("podium");
        assertThat(Statues.suggest(new String[]{"place", "richest", "1", ""})).isEmpty();
        assertThat(Statues.suggest(new String[0])).isEmpty();
    }
}
