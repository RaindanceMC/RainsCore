package de.raindancer.core.ui.tree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TreeGlyphsTest {

    /** Plays the string back as the game lays it out: where each sprite lands, and where the pen ends. */
    private record Played(List<int[]> sprites, int end) {
    }

    private static Played play(String glyphs) {
        int x = TreeGlyphs.START;
        List<int[]> sprites = new java.util.ArrayList<>();
        for (char c : glyphs.toCharArray()) {
            Integer space = TreeGlyphs.advance(c);
            if (space != null) {
                x += space;
                continue;
            }
            int index = c - TreeGlyphs.FIRST_SPRITE;
            sprites.add(new int[]{index / TreeGlyphs.ROWS_PER_SPRITE, index % TreeGlyphs.ROWS_PER_SPRITE, x});
            x += TreeGrid.SPRITE + 1;
        }
        return new Played(sprites, x);
    }

    @Test
    @DisplayName("each sprite lands on its slot, in its row's character, and the pen goes back to where the title starts")
    void spritesLandOnTheirSlots() {
        String glyphs = TreeGlyphs.draw(List.of(
                new TreeGlyphs.Placement(TreeSprite.FRAME_OWNED, 0, 0),
                new TreeGlyphs.Placement(TreeSprite.ACROSS_LIT, 0, 1),
                new TreeGlyphs.Placement(TreeSprite.FRAME_OPEN, 4, 8),
                new TreeGlyphs.Placement(TreeSprite.FALLING_DIM, 3, 3)));
        Played played = play(glyphs);
        assertThat(played.sprites()).hasSize(4);
        assertThat(played.sprites().get(0)).containsExactly(TreeSprite.FRAME_OWNED.ordinal(), 0, TreeGrid.spriteX(0));
        assertThat(played.sprites().get(1)).containsExactly(TreeSprite.ACROSS_LIT.ordinal(), 0, TreeGrid.spriteX(1));
        assertThat(played.sprites().get(2)).containsExactly(TreeSprite.FRAME_OPEN.ordinal(), 4, TreeGrid.spriteX(8));
        assertThat(played.sprites().get(3)).containsExactly(TreeSprite.FALLING_DIM.ordinal(), 3, TreeGrid.spriteX(3));
        assertThat(played.end()).isEqualTo(TreeGlyphs.START);
    }

    @Test
    @DisplayName("nothing to draw is nothing at all")
    void nothing() {
        assertThat(TreeGlyphs.draw(List.of())).isEmpty();
    }

    @Test
    @DisplayName("every move, however far, is made of the font's spaces")
    void anyDistance() {
        for (int distance = -200; distance <= 200; distance++) {
            int moved = 0;
            for (char c : TreeGlyphs.move(distance).toCharArray()) {
                moved += TreeGlyphs.advance(c);
            }
            assertThat(moved).as("move " + distance).isEqualTo(distance);
        }
    }
}
