package de.raindancer.core.world.activity;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityRulesTest {

    @Test
    @DisplayName("terrain: stone digs, dirt and sand excavate, logs chop, mushrooms and flowers forage; none twice")
    void terrain() {
        assertThat(Terrain.kindOf(Material.STONE)).contains(ActivityKind.DIG);
        assertThat(Terrain.kindOf(Material.DEEPSLATE)).contains(ActivityKind.DIG);
        assertThat(Terrain.kindOf(Material.NETHERRACK)).contains(ActivityKind.DIG);
        assertThat(Terrain.kindOf(Material.DIRT)).contains(ActivityKind.EXCAVATE);
        assertThat(Terrain.kindOf(Material.GRAVEL)).contains(ActivityKind.EXCAVATE);
        assertThat(Terrain.kindOf(Material.OAK_LOG)).contains(ActivityKind.CHOP);
        assertThat(Terrain.kindOf(Material.CRIMSON_STEM)).contains(ActivityKind.CHOP);
        assertThat(Terrain.kindOf(Material.STRIPPED_OAK_LOG)).as("a player made that").isEmpty();
        assertThat(Terrain.kindOf(Material.RED_MUSHROOM)).contains(ActivityKind.FORAGE);
        assertThat(Terrain.kindOf(Material.POPPY)).contains(ActivityKind.FORAGE);
        assertThat(Terrain.kindOf(Material.COBBLESTONE)).as("a generator makes that").isEmpty();
        assertThat(Terrain.kindOf(Material.IRON_ORE)).as("ores are MINE").isEmpty();
        assertThat(Terrain.watched()).contains(Material.STONE, Material.OAK_LOG, Material.POPPY);
    }

    @Test
    @DisplayName("a recipe of one kind of ingredient — ingot to block and back, logs to planks — is no craft")
    void crafts() {
        assertThat(Crafts.counts(List.of(Material.IRON_INGOT, Material.IRON_INGOT, Material.IRON_INGOT))).isFalse();
        assertThat(Crafts.counts(List.of(Material.OAK_LOG))).isFalse();
        assertThat(Crafts.counts(Arrays.asList(Material.STICK, null, Material.IRON_INGOT, Material.AIR))).isTrue();
        assertThat(Crafts.counts(List.of())).isFalse();
    }

    @Test
    @DisplayName("a shift-click crafts as many times as the scarcest ingredient allows")
    void shiftClick() {
        assertThat(Crafts.times(List.of(12, 64, 5))).isEqualTo(5);
        assertThat(Crafts.times(List.of())).isZero();
    }

    @Test
    @DisplayName("building on a spot the same player built on within ten minutes does not count again")
    void spots() {
        RecentSpots spots = new RecentSpots(600_000L, 4);
        UUID me = UUID.randomUUID();
        UUID you = UUID.randomUUID();
        assertThat(spots.firstTime(me, "w", 1, 2, 3, 0)).isTrue();
        assertThat(spots.firstTime(me, "w", 1, 2, 3, 1_000)).isFalse();
        assertThat(spots.firstTime(you, "w", 1, 2, 3, 1_000)).as("somebody else's spot").isTrue();
        assertThat(spots.firstTime(me, "w", 1, 2, 3, 700_000)).as("ten minutes on").isTrue();
        for (int x = 10; x < 20; x++) {
            spots.firstTime(me, "w", x, 0, 0, 800_000);
        }
        assertThat(spots.firstTime(me, "w", 1, 2, 3, 800_001)).as("only the latest few are remembered")
                .isTrue();
    }

    @Test
    @DisplayName("travel: the blocks walked, sprinted, swum and climbed since last asked; the first asking only remembers")
    void travel() {
        TravelMeter meter = new TravelMeter();
        UUID me = UUID.randomUUID();
        assertThat(meter.since(me, 10_000)).isZero();
        assertThat(meter.since(me, 25_000)).isEqualTo(150);
        assertThat(meter.since(me, 20_000)).as("a reset counter never counts backwards").isZero();
        assertThat(meter.since(me, 20_099)).isZero();
        assertThat(meter.since(me, 20_200)).isEqualTo(2);
    }
}
