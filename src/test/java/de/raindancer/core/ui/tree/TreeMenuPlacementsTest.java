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

    @Test
    @DisplayName("on a scrolled canvas only what the window shows is drawn, where the window shows it; badges have their own frame")
    void scrolled() {
        TreeMenu.Page page = new TreeMenu.Page(Component.text("x"), List.of(
                node("far", 0, 0, NodeState.MAXED), node("a", 6, 3, NodeState.MAXED),
                new TreeMenu.Node("badge", 7, 3, NodeState.OPEN, null, true)),
                List.of(new TreeMenu.Edge("far", "a"), new TreeMenu.Edge("a", "badge")), null, null);
        TreeView view = TreeView.of(List.of(new int[]{0, 0}, new int[]{7, 3}));
        view.lookAt(6, 3);
        // The canvas is 8 x 4, so the window stops at column 3, row 1: "a" is its fourth column, third row.
        assertThat(TreeMenu.placements(page, view)).containsExactly(
                new TreeGlyphs.Placement(TreeSprite.ACROSS_DIM, 4, 7),
                new TreeGlyphs.Placement(TreeSprite.FRAME_MAXED, 4, 6),
                new TreeGlyphs.Placement(TreeSprite.MASTERY_OPEN, 4, 8));
    }
}
