package de.raindancer.core.world.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What a player has to carry to put a block up — not always the block's own item, and not always one. */
class BuildBillTest {

    @Test
    @DisplayName("a plain block costs one of itself")
    void plain() {
        assertThat(BuildBill.costOf("minecraft:oak_planks")).containsExactly(new BuildBill.Cost("minecraft:oak_planks", 1));
    }

    @Test
    @DisplayName("nothing costs nothing")
    void air() {
        assertThat(BuildBill.costOf("minecraft:air")).isEmpty();
        assertThat(BuildBill.costOf("minecraft:cave_air")).isEmpty();
    }

    @Test
    @DisplayName("a double slab is two slabs")
    void doubleSlab() {
        assertThat(BuildBill.costOf("minecraft:oak_slab[type=double,waterlogged=false]"))
                .containsExactly(new BuildBill.Cost("minecraft:oak_slab", 2));
        assertThat(BuildBill.costOf("minecraft:oak_slab[type=top,waterlogged=false]"))
                .containsExactly(new BuildBill.Cost("minecraft:oak_slab", 1));
    }

    @Test
    @DisplayName("a door, a tall flower and a bed are paid for once, on their first half")
    void halves() {
        assertThat(BuildBill.costOf("minecraft:oak_door[facing=east,half=upper,hinge=left,open=false,powered=false]")).isEmpty();
        assertThat(BuildBill.costOf("minecraft:oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]"))
                .containsExactly(new BuildBill.Cost("minecraft:oak_door", 1));
        assertThat(BuildBill.costOf("minecraft:peony[half=upper]")).isEmpty();
        assertThat(BuildBill.costOf("minecraft:red_bed[facing=north,occupied=false,part=head]")).isEmpty();
        assertThat(BuildBill.costOf("minecraft:red_bed[facing=north,occupied=false,part=foot]"))
                .containsExactly(new BuildBill.Cost("minecraft:red_bed", 1));
        // Stairs say half=top|bottom: both halves of the word, but a whole stair each.
        assertThat(BuildBill.costOf("minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]"))
                .containsExactly(new BuildBill.Cost("minecraft:oak_stairs", 1));
    }

    @Test
    @DisplayName("what hangs on a wall is the item that stands on the floor")
    void wallVariants() {
        assertThat(BuildBill.costOf("minecraft:wall_torch[facing=east]"))
                .containsExactly(new BuildBill.Cost("minecraft:torch", 1));
        assertThat(BuildBill.costOf("minecraft:soul_wall_torch[facing=east]"))
                .containsExactly(new BuildBill.Cost("minecraft:soul_torch", 1));
        assertThat(BuildBill.costOf("minecraft:cherry_wall_sign[facing=east,waterlogged=false]"))
                .containsExactly(new BuildBill.Cost("minecraft:cherry_sign", 1));
        assertThat(BuildBill.costOf("minecraft:cherry_wall_hanging_sign[facing=east,waterlogged=false]"))
                .containsExactly(new BuildBill.Cost("minecraft:cherry_hanging_sign", 1));
        assertThat(BuildBill.costOf("minecraft:white_wall_banner[facing=east]"))
                .containsExactly(new BuildBill.Cost("minecraft:white_banner", 1));
        // A wall itself is not a wall variant.
        assertThat(BuildBill.costOf("minecraft:cobblestone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]"))
                .containsExactly(new BuildBill.Cost("minecraft:cobblestone_wall", 1));
    }

    @Test
    @DisplayName("blocks placed with a different item: redstone, string, seeds, buckets")
    void placedWithSomethingElse() {
        assertThat(BuildBill.costOf("minecraft:redstone_wire[east=none,north=side,power=0,south=none,west=none]"))
                .containsExactly(new BuildBill.Cost("minecraft:redstone", 1));
        assertThat(BuildBill.costOf("minecraft:tripwire[attached=false]"))
                .containsExactly(new BuildBill.Cost("minecraft:string", 1));
        assertThat(BuildBill.costOf("minecraft:wheat[age=7]"))
                .containsExactly(new BuildBill.Cost("minecraft:wheat_seeds", 1));
        assertThat(BuildBill.costOf("minecraft:water[level=0]"))
                .containsExactly(new BuildBill.Cost("minecraft:water_bucket", 1));
        assertThat(BuildBill.costOf("minecraft:water[level=3]")).isEmpty();
        assertThat(BuildBill.costOf("minecraft:farmland[moisture=7]"))
                .containsExactly(new BuildBill.Cost("minecraft:dirt", 1));
    }

    @Test
    @DisplayName("a potted plant is a pot and the plant")
    void pots() {
        assertThat(BuildBill.costOf("minecraft:potted_cherry_sapling")).containsExactly(
                new BuildBill.Cost("minecraft:flower_pot", 1), new BuildBill.Cost("minecraft:cherry_sapling", 1));
        assertThat(BuildBill.costOf("minecraft:potted_azalea_bush")).containsExactly(
                new BuildBill.Cost("minecraft:flower_pot", 1), new BuildBill.Cost("minecraft:azalea", 1));
    }

    @Test
    @DisplayName("several in one block: candles, pickles, petals, snow layers")
    void counted() {
        assertThat(BuildBill.costOf("minecraft:pink_candle[candles=3,lit=false,waterlogged=false]"))
                .containsExactly(new BuildBill.Cost("minecraft:pink_candle", 3));
        assertThat(BuildBill.costOf("minecraft:pink_petals[facing=north,flower_amount=4]"))
                .containsExactly(new BuildBill.Cost("minecraft:pink_petals", 4));
        assertThat(BuildBill.costOf("minecraft:snow[layers=2]"))
                .containsExactly(new BuildBill.Cost("minecraft:snow", 2));
    }

    @Test
    @DisplayName("things nobody can place cost nothing: fire, portals, piston heads")
    void unplaceable() {
        assertThat(BuildBill.costOf("minecraft:fire[age=0,east=false,north=false,south=false,up=false,west=false]")).isEmpty();
        assertThat(BuildBill.costOf("minecraft:nether_portal[axis=x]")).isEmpty();
        assertThat(BuildBill.costOf("minecraft:piston_head[facing=up,short=false,type=normal]")).isEmpty();
    }

    @Test
    @DisplayName("a whole list adds up per item, most needed first")
    void totals() {
        Map<String, Integer> bill = BuildBill.of(List.of("minecraft:stone", "minecraft:stone",
                "minecraft:oak_slab[type=double]", "minecraft:oak_slab[type=bottom]", "minecraft:air"));
        assertThat(bill).containsExactly(Map.entry("minecraft:oak_slab", 3), Map.entry("minecraft:stone", 2));
    }
}
