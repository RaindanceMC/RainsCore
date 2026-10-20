package de.raindancer.core.content.items;

import de.raindancer.core.ui.choose.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("What a search can find in one item")
class ItemWordsTest {

    @Test
    @DisplayName("found by its material, its given name, an enchantment or a lore line")
    void finds() {
        ItemWords sword = new ItemWords("Diamond Sword", "Excalibur", List.of("Sharpness", "Forged in the Fold"),
                Category.COMBAT);
        assertThat(sword.matches("diamond")).isTrue();
        assertThat(sword.matches("excal")).isTrue();
        assertThat(sword.matches("sharp sword")).isTrue();
        assertThat(sword.matches("fold")).isTrue();
        assertThat(sword.matches("gold")).isFalse();
        assertThat(sword.shown()).isEqualTo("Excalibur");
        assertThat(new ItemWords("Oak Log", null, List.of(), Category.BUILDING_BLOCKS).shown()).isEqualTo("Oak Log");
    }
}
