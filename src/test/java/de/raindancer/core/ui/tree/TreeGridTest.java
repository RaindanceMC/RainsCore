package de.raindancer.core.ui.tree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TreeGridTest {

    @Test
    @DisplayName("a node at column c and row r of the tree sits in every other slot: slot row 2r, slot column 2c")
    void nodesSitInEveryOtherSlot() {
        assertThat(TreeGrid.slot(0, 0)).isEqualTo(0);
        assertThat(TreeGrid.slot(4, 0)).isEqualTo(8);
        assertThat(TreeGrid.slot(2, 1)).isEqualTo(2 * 9 + 4);
        assertThat(TreeGrid.slot(4, 2)).isEqualTo(4 * 9 + 8);
    }

    @Test
    @DisplayName("a tree is five columns by three rows; a node outside them is refused")
    void outsideIsRefused() {
        assertThatThrownBy(() -> TreeGrid.slot(5, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TreeGrid.slot(0, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TreeGrid.slot(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an edge between neighbours runs through the slot between them, straight or diagonal")
    void edgesRunBetween() {
        assertThat(TreeGrid.link(0, 0, 1, 0)).isEqualTo(new TreeGrid.Link(0, 1, TreeGrid.Shape.ACROSS));
        assertThat(TreeGrid.link(1, 0, 0, 0)).isEqualTo(new TreeGrid.Link(0, 1, TreeGrid.Shape.ACROSS));
        assertThat(TreeGrid.link(2, 0, 2, 1)).isEqualTo(new TreeGrid.Link(1, 4, TreeGrid.Shape.DOWN));
        assertThat(TreeGrid.link(1, 1, 2, 2)).isEqualTo(new TreeGrid.Link(3, 3, TreeGrid.Shape.FALLING));
        assertThat(TreeGrid.link(2, 1, 1, 2)).isEqualTo(new TreeGrid.Link(3, 3, TreeGrid.Shape.RISING));
        assertThat(TreeGrid.link(1, 2, 2, 1)).isEqualTo(new TreeGrid.Link(3, 3, TreeGrid.Shape.RISING));
    }

    @Test
    @DisplayName("nodes that are not neighbours cannot be joined: the line would run through another node")
    void farNodesCannotBeJoined() {
        assertThatThrownBy(() -> TreeGrid.link(0, 0, 2, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TreeGrid.link(0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TreeGrid.link(0, 0, 1, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a sprite is 26 pixels square, centred on its slot, so a line reaches into the frames at both ends")
    void spritesAreCentredOnTheirSlot() {
        assertThat(TreeGrid.spriteX(0)).isEqualTo(3);
        assertThat(TreeGrid.spriteX(8)).isEqualTo(3 + 8 * 18);
        assertThat(TreeGrid.spriteAscent(0)).isEqualTo(0);
        assertThat(TreeGrid.spriteAscent(4)).isEqualTo(-72);
    }

    @Test
    @DisplayName("every node's slot and every link's slot is different, so nothing is drawn twice in one place")
    void slotsDoNotCollide() {
        List<Integer> nodes = new java.util.ArrayList<>();
        for (int column = 0; column < TreeGrid.COLUMNS; column++) {
            for (int row = 0; row < TreeGrid.ROWS; row++) {
                nodes.add(TreeGrid.slot(column, row));
            }
        }
        TreeGrid.Link link = TreeGrid.link(0, 0, 1, 1);
        assertThat(nodes).doesNotContain(link.slotRow() * 9 + link.slotColumn());
    }
}
