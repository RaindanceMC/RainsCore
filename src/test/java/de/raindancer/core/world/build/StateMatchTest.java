package de.raindancer.core.world.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Whether a block in the world is the block a plan asks for — strict about what a builder chose, not about what the game works out. */
class StateMatchTest {

    @Test
    @DisplayName("the same block is a match, with or without the namespace")
    void same() {
        assertThat(StateMatch.matches("minecraft:oak_planks", "oak_planks")).isTrue();
        assertThat(StateMatch.matches("minecraft:oak_planks", "minecraft:spruce_planks")).isFalse();
    }

    @Test
    @DisplayName("which way a stair faces and whether it is upside down count; its corner shape does not")
    void stairs() {
        String planned = "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]";
        assertThat(StateMatch.matches(planned,
                "minecraft:oak_stairs[facing=north,half=bottom,shape=outer_left,waterlogged=true]")).isTrue();
        assertThat(StateMatch.matches(planned,
                "minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")).isFalse();
        assertThat(StateMatch.matches(planned,
                "minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]")).isFalse();
    }

    @Test
    @DisplayName("fences, panes and walls connect by themselves, so their sides never count")
    void connections() {
        assertThat(StateMatch.matches("minecraft:oak_fence[east=true,north=false,south=false,west=true]",
                "minecraft:oak_fence[east=false,north=true,south=true,west=false]")).isTrue();
    }

    @Test
    @DisplayName("an open door is still the right door; a door on the wrong hinge is not")
    void doors() {
        String planned = "minecraft:oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]";
        assertThat(StateMatch.matches(planned,
                "minecraft:oak_door[facing=east,half=lower,hinge=left,open=true,powered=false]")).isTrue();
        assertThat(StateMatch.matches(planned,
                "minecraft:oak_door[facing=east,half=lower,hinge=right,open=false,powered=false]")).isFalse();
    }

    @Test
    @DisplayName("a top slab is not a bottom slab, and a double slab is neither")
    void slabs() {
        assertThat(StateMatch.matches("minecraft:stone_slab[type=top]", "minecraft:stone_slab[type=bottom]")).isFalse();
        assertThat(StateMatch.matches("minecraft:stone_slab[type=double]", "minecraft:stone_slab[type=double,waterlogged=false]")).isTrue();
    }

    @Test
    @DisplayName("leaves a player placed are the leaves the plan wants, whatever their distance says")
    void leaves() {
        assertThat(StateMatch.matches("minecraft:cherry_leaves[distance=7,persistent=true,waterlogged=false]",
                "minecraft:cherry_leaves[distance=1,persistent=true,waterlogged=false]")).isTrue();
    }

    @Test
    @DisplayName("air, cave air and void air are all nothing")
    void airs() {
        assertThat(StateMatch.matches("minecraft:air", "minecraft:cave_air")).isTrue();
        assertThat(StateMatch.isAir("minecraft:void_air")).isTrue();
        assertThat(StateMatch.isAir("minecraft:glass")).isFalse();
    }

    @Test
    @DisplayName("logs lying the other way are wrong")
    void axis() {
        assertThat(StateMatch.matches("minecraft:oak_log[axis=x]", "minecraft:oak_log[axis=z]")).isFalse();
        assertThat(StateMatch.matches("minecraft:oak_log[axis=y]", "minecraft:oak_log[axis=y]")).isTrue();
    }

    @Test
    @DisplayName("the block id of a state, without its properties")
    void idOf() {
        assertThat(StateMatch.id("oak_stairs[facing=north]")).isEqualTo("minecraft:oak_stairs");
        assertThat(StateMatch.id("minecraft:stone")).isEqualTo("minecraft:stone");
        assertThat(StateMatch.property("minecraft:oak_stairs[facing=north,half=top]", "half")).contains("top");
        assertThat(StateMatch.property("minecraft:stone", "half")).isEmpty();
    }
}
