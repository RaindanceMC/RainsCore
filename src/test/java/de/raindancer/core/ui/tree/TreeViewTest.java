package de.raindancer.core.ui.tree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TreeViewTest {

    @Test
    @DisplayName("a canvas no bigger than the window does not scroll")
    void small() {
        TreeView view = TreeView.of(List.of(new int[]{0, 0}, new int[]{4, 2}));
        assertThat(view.canScrollLeft()).isFalse();
        assertThat(view.canScrollRight()).isFalse();
        assertThat(view.canScrollUp()).isFalse();
        assertThat(view.canScrollDown()).isFalse();
        assertThat(view.shows(4, 2)).isTrue();
    }

    @Test
    @DisplayName("a wide and tall canvas scrolls a column or a row at a time, never past its edges")
    void scrolling() {
        TreeView view = TreeView.of(List.of(new int[]{0, 0}, new int[]{11, 7}));
        assertThat(view.canScrollRight()).isTrue();
        assertThat(view.canScrollDown()).isTrue();
        view.scroll(1, 0);
        assertThat(view.column()).isEqualTo(1);
        assertThat(view.shows(0, 0)).isFalse();
        assertThat(view.shows(5, 0)).isTrue();
        assertThat(view.local(5, 0)).containsExactly(4, 0);
        view.scroll(99, 99);
        assertThat(view.column()).as("the last column is the window's right edge").isEqualTo(7);
        assertThat(view.row()).isEqualTo(5);
        assertThat(view.canScrollRight()).isFalse();
        assertThat(view.canScrollDown()).isFalse();
        assertThat(view.hiddenRight()).isZero();
        assertThat(view.hiddenLeft()).isEqualTo(7);
        view.scroll(-99, -99);
        assertThat(view.column()).isZero();
        assertThat(view.row()).isZero();
    }

    @Test
    @DisplayName("looking at a node puts it in the window, as near the top left as the canvas allows")
    void lookAt() {
        TreeView view = TreeView.of(List.of(new int[]{0, 0}, new int[]{11, 7}));
        view.lookAt(9, 6);
        assertThat(view.shows(9, 6)).isTrue();
        assertThat(view.column()).isEqualTo(7);
        assertThat(view.row()).isEqualTo(5);
        view.lookAt(2, 1);
        assertThat(view.column()).isEqualTo(2);
        assertThat(view.row()).isEqualTo(1);
    }
}
