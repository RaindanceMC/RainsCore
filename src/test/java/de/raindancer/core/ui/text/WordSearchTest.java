package de.raindancer.core.ui.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Searching by typed words")
class WordSearchTest {

    @Test
    @DisplayName("every typed word somewhere, in any order and case; underscores are spaces")
    void allWords() {
        assertThat(WordSearch.matches("sword dia", "Diamond Sword")).isTrue();
        assertThat(WordSearch.matches("oak_log", "Oak Log")).isTrue();
        assertThat(WordSearch.matches("OAK", "dark_oak_planks")).isTrue();
        assertThat(WordSearch.matches("sharp sword", "Diamond Sword", "Sharpness")).isTrue();
        assertThat(WordSearch.matches("gold sword", "Diamond Sword")).isFalse();
    }

    @Test
    @DisplayName("nothing typed finds everything; nothing to search finds nothing typed")
    void blanks() {
        assertThat(WordSearch.matches("", "Anything")).isTrue();
        assertThat(WordSearch.matches("  ", "Anything")).isTrue();
        assertThat(WordSearch.matches(null, "Anything")).isTrue();
        assertThat(WordSearch.matches("stone")).isFalse();
        assertThat(WordSearch.matches("stone", (String) null)).isFalse();
    }
}
