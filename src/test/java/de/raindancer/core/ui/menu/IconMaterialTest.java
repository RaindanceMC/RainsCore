package de.raindancer.core.ui.menu;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A block with no item (a portal, water, fire) asked for as an icon used to take the whole page down with it. */
class IconMaterialTest {

    @Test
    @DisplayName("an item is shown as itself")
    void items() {
        assertThat(Icons.shown(Material.COMPASS)).isEqualTo(Material.COMPASS);
        assertThat(Icons.shown(Material.OBSIDIAN)).isEqualTo(Material.OBSIDIAN);
    }

    @Test
    @DisplayName("a block that is not an item is shown as the nearest thing that is")
    void blocksWithoutAnItem() {
        assertThat(Icons.shown(Material.NETHER_PORTAL)).isEqualTo(Material.OBSIDIAN);
        assertThat(Icons.shown(Material.END_PORTAL)).isEqualTo(Material.END_PORTAL_FRAME);
        assertThat(Icons.shown(Material.WATER)).isEqualTo(Material.WATER_BUCKET);
        assertThat(Icons.shown(Material.LAVA)).isEqualTo(Material.LAVA_BUCKET);
        assertThat(Icons.shown(Material.FIRE)).isEqualTo(Material.FLINT_AND_STEEL);
    }

    @Test
    @DisplayName("anything else that cannot be held, nothing included, is still a button")
    void anythingElse() {
        assertThat(Icons.shown(Material.AIR)).isEqualTo(Material.STONE);
        assertThat(Icons.shown(Material.MOVING_PISTON)).isEqualTo(Material.STONE);
        assertThat(Icons.shown(null)).isEqualTo(Material.STONE);
        for (Material material : Material.values()) {
            if (!material.isLegacy()) {
                assertThat(Icons.shown(material).isItem()).as(material.name()).isTrue();
            }
        }
    }
}
