package de.raindancer.core.ui.menu;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArrowTest {

    @Test
    @DisplayName("every arrow has its painted icon in the pack: what the client asks for, its model and its texture")
    void everyArrowShips() {
        for (Arrow arrow : Arrow.values()) {
            String name = arrow.model().value();
            assertThat(arrow.model().namespace()).isEqualTo(MenuStyle.NAMESPACE);
            assertThat(MenuPack.files()).as(arrow.name()).contains(
                    "assets/rainscore/items/" + name + ".json",
                    "assets/rainscore/models/item/" + name + ".json",
                    "assets/rainscore/textures/item/" + name + ".png");
        }
    }

    @Test
    @DisplayName("the painted icon only for a viewer wearing the pack; anyone else keeps the vanilla arrow")
    void onlyWithThePack() {
        assertThat(Arrow.shown("left", true)).contains(Arrow.LEFT);
        assertThat(Arrow.shown("left", false)).isEmpty();
        assertThat(Arrow.shown(null, true)).isEmpty();
        assertThat(Arrow.shown("sideways", true)).as("a tag from a newer Core").isEmpty();
    }

    @Test
    @DisplayName("without the pack each still looks like an arrow: Back and scrolling the plain one, pages the spectral")
    void vanillaUnderneath() {
        assertThat(Arrow.BACK.vanilla()).isEqualTo(Material.ARROW);
        assertThat(Arrow.UP.vanilla()).isEqualTo(Material.ARROW);
        assertThat(Arrow.DOWN.vanilla()).isEqualTo(Material.ARROW);
        assertThat(Arrow.LEFT.vanilla()).isEqualTo(Material.SPECTRAL_ARROW);
        assertThat(Arrow.RIGHT.vanilla()).isEqualTo(Material.SPECTRAL_ARROW);
        assertThat(Arrow.LEFT.tag()).isEqualTo("left");
    }
}
