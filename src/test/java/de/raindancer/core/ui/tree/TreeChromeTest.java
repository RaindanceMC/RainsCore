package de.raindancer.core.ui.tree;

import de.raindancer.core.ui.menu.MenuLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TreeChromeTest {

    @Test
    @DisplayName("the chrome row is symmetrical: up and down sit as far from the page button as left and right")
    void symmetrical() {
        int page = MenuLayout.CHROME_PAGE;
        assertThat(page - MenuLayout.CHROME_PREVIOUS).isEqualTo(MenuLayout.CHROME_NEXT - page);
        assertThat(page - TreeMenu.CHROME_UP).isEqualTo(TreeMenu.CHROME_DOWN - page);
    }

    @Test
    @DisplayName("help moves aside for the down arrow, onto a slot nothing else of the row takes")
    void helpMovesAside() {
        assertThat(TreeMenu.CHROME_TREE_HELP).isNotIn(Set.of(MenuLayout.CHROME_BACK, MenuLayout.CHROME_HOME,
                TreeMenu.CHROME_UP, MenuLayout.CHROME_PREVIOUS, MenuLayout.CHROME_PAGE, MenuLayout.CHROME_NEXT,
                TreeMenu.CHROME_DOWN, MenuLayout.CHROME_CLOSE));
    }
}
