package de.raindancer.core.ui.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NameSearchTest {

    @Test
    @DisplayName("every word typed has to start a word of the name, in any order and any case")
    void words() {
        assertThat(NameSearch.score("diamond", "Diamond Sword")).isPresent();
        assertThat(NameSearch.score("sword dia", "Diamond Sword")).isPresent();
        assertThat(NameSearch.score("DIAMOND_SWORD", "Diamond Sword")).isPresent();
        assertThat(NameSearch.score("iron sword", "Diamond Sword")).isEmpty();
        assertThat(NameSearch.score("mond", "Diamond Sword")).as("the middle of a word is no match").isEmpty();
    }

    @Test
    @DisplayName("a level can be typed as a number: sharp 5 finds Sharpness V")
    void levels() {
        assertThat(NameSearch.score("sharp 5", "Sharpness V")).isPresent();
        assertThat(NameSearch.score("sharpness v", "Sharpness V")).isPresent();
        assertThat(NameSearch.score("sharp 4", "Sharpness V")).isEmpty();
        assertThat(NameSearch.score("healing 2", "Splash Potion of Healing II")).isPresent();
    }

    @Test
    @DisplayName("the closer the name, the earlier it comes: whole name, then the start, then anywhere")
    void ranking() {
        int exact = NameSearch.score("diamond", "Diamond").orElseThrow();
        int start = NameSearch.score("diamond", "Diamond Sword").orElseThrow();
        int later = NameSearch.score("diamond", "Block of Diamond").orElseThrow();

        assertThat(exact).isLessThan(start);
        assertThat(start).isLessThan(later);
    }

    @Test
    @DisplayName("nothing typed finds nothing rather than everything")
    void blank() {
        assertThat(NameSearch.score("  ", "Diamond")).isEmpty();
        assertThat(NameSearch.score(null, "Diamond")).isEmpty();
    }

    @Test
    @DisplayName("a typo still finds it: a letter off, missing, extra or swapped; two in a long word")
    void typos() {
        assertThat(NameSearch.score("diamnd", "Diamond")).isPresent();
        assertThat(NameSearch.score("netherite swrod", "Netherite Sword")).isPresent();
        assertThat(NameSearch.score("sharpnes 5", "Sharpness V")).isPresent();
        assertThat(NameSearch.score("enchanted gloden apple", "Enchanted Golden Apple")).isPresent();
        assertThat(NameSearch.score("efficeincy", "Efficiency V")).as("two in a long word").isPresent();
        assertThat(NameSearch.score("dimnd", "Diamond")).as("two in a short word is something else").isEmpty();
        assertThat(NameSearch.score("cat", "Cod")).as("short words are matched exactly").isEmpty();
    }

    @Test
    @DisplayName("among typos, as among the real spellings: the whole name first, then one starting with it, then the rest")
    void typosRankLikeSpellings() {
        int whole = NameSearch.score("diamnd", "Diamond").orElseThrow();
        int start = NameSearch.score("diamnd", "Diamond Sword").orElseThrow();
        int later = NameSearch.score("diamnd", "Deepslate Diamond Ore").orElseThrow();
        assertThat(whole).isLessThan(start);
        assertThat(start).isLessThan(later);
        assertThat(whole).as("still after every real spelling")
                .isGreaterThan(NameSearch.score("diamond", "Block of Diamond").orElseThrow());
    }

    @Test
    @DisplayName("a typo ranks below the real spelling")
    void typosRankLast() {
        assertThat(NameSearch.score("diamnd", "Diamond").orElseThrow())
                .isGreaterThan(NameSearch.score("diamond", "Block of Diamond").orElseThrow());
    }

    @Test
    @DisplayName("words that say nothing are left out: block of diamond finds the Diamond Block")
    void filler() {
        assertThat(NameSearch.score("block of diamond", "Diamond Block")).isPresent();
        assertThat(NameSearch.score("potion of the healing", "Potion of Healing")).isPresent();
    }
}
