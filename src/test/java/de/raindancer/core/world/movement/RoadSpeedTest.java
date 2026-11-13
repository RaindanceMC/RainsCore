package de.raindancer.core.world.movement;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RoadSpeedTest {

    @Test
    @DisplayName("shipped off: both at 100 percent, dirt path and gravel as roads")
    void shippedOff() {
        MountSettings d = MountSettings.DEFAULTS;
        assertThat(d.roadPercent()).isEqualTo(100);
        assertThat(d.offRoadPercent()).isEqualTo(100);
        assertThat(RoadSpeed.roads(d.roadBlocks())).containsExactlyInAnyOrder(Material.DIRT_PATH, Material.GRAVEL);
        assertThat(RoadSpeed.isOff(d.roadPercent(), d.offRoadPercent())).isTrue();
    }

    @Test
    @DisplayName("road blocks are read by name, any case, by comma or space; what is no block is skipped")
    void readsRoadBlocks() {
        assertThat(RoadSpeed.roads(" Dirt_Path, minecraft:gravel  stone_bricks, diamond, nonsense,"))
                .containsExactlyInAnyOrder(Material.DIRT_PATH, Material.GRAVEL, Material.STONE_BRICKS);
        assertThat(RoadSpeed.roads(null)).isEmpty();
    }

    @Test
    @DisplayName("on a road the road's percent, anywhere else the other one")
    void multiplier() {
        Set<Material> roads = Set.of(Material.DIRT_PATH, Material.GRAVEL);
        assertThat(RoadSpeed.multiplier(Material.DIRT_PATH, roads, 160, 110)).isCloseTo(1.6, within(1e-9));
        assertThat(RoadSpeed.multiplier(Material.GRASS_BLOCK, roads, 160, 110)).isCloseTo(1.1, within(1e-9));
        assertThat(RoadSpeed.multiplier(Material.AIR, roads, 160, 110)).isCloseTo(1.1, within(1e-9));
    }

    @Test
    @DisplayName("the block looked at is the one the hooves stand on — a dirt path is lower than a full block")
    void standsOn() {
        // on a full block at y=64 the feet are at 64.0: the block below
        assertThat(RoadSpeed.standingBlockY(64.0)).isEqualTo(63);
        // on a dirt path at y=63 the feet are at 63.9375: the path itself
        assertThat(RoadSpeed.standingBlockY(63.9375)).isEqualTo(63);
        assertThat(RoadSpeed.standingBlockY(-0.0)).isEqualTo(-1);
    }

    @Test
    @DisplayName("an average horse (0.225) at 160 percent is about 15.5 blocks a second")
    void blocksPerSecond() {
        assertThat(RoadSpeed.blocksPerSecond(0.225 * 1.6)).isCloseTo(15.5, within(0.2));
    }
}
