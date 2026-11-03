package de.raindancer.core.ui.tree;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TreeMenuPlacementsTest {

    private static TreeMenu.Node node(String id, int column, int row, NodeState state) {
        return new TreeMenu.Node(id, column, row, state, null);
    }

    @Test
    @DisplayName("lines come first and frames over them; a line is lit only when both its ends are taken")
    void linesThenFrames() {
        TreeMenu.Page page = new TreeMenu.Page(Component.text("x"), List.of(
                node("a", 0, 0, NodeState.MAXED), node("b", 1, 0, NodeState.OWNED), node("c", 1, 1, NodeState.OPEN)),
                List.of(new TreeMenu.Edge("a", "b"), new TreeMenu.Edge("a", "c"), new TreeMenu.Edge("a", "gone")),
                null, null);
        List<TreeGlyphs.Placement> placements = TreeMenu.placements(page);
        assertThat(placements).containsExactly(
                new TreeGlyphs.Placement(TreeSprite.ACROSS_LIT, 0, 1),
                new TreeGlyphs.Placement(TreeSprite.FALLING_DIM, 1, 1),
                new TreeGlyphs.Placement(TreeSprite.FRAME_MAXED, 0, 0),
                new TreeGlyphs.Placement(TreeSprite.FRAME_OWNED, 0, 2),
                new TreeGlyphs.Placement(TreeSprite.FRAME_OPEN, 2, 2));
    }
}
