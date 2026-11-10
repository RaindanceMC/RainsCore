package de.raindancer.core.world.companion;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.EnumSet;
import java.util.Set;

/** The blocks of a world as {@link Pathing} sees them — for a companion's legs, or a guide walking a player round. */
public final class Terrains {

    private static final Set<Material> HARMFUL = EnumSet.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
            Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.CACTUS,
            Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.WITHER_ROSE);

    private Terrains() {
    }

    /** Unloaded chunks and the world's floor and ceiling count as solid: a way never leads into them. */
    public static Pathing.Terrain of(World world) {
        return (x, y, z) -> {
            if (!world.isChunkLoaded(x >> 4, z >> 4) || y < world.getMinHeight() || y >= world.getMaxHeight()) {
                return Pathing.Ground.SOLID;
            }
            Block block = world.getBlockAt(x, y, z);
            Pathing.Ground ground = ground(block);
            if (ground == Pathing.Ground.OPEN && y - 1 >= world.getMinHeight()) {
                Block below = world.getBlockAt(x, y - 1, z);
                // fences and walls stand a block and a half: the air over them is no place to step
                if (!below.isPassable() && below.getBoundingBox().getHeight() > 1.05) {
                    return Pathing.Ground.SOLID;
                }
            }
            return ground;
        };
    }

    static Pathing.Ground ground(Block block) {
        Material type = block.getType();
        if (HARMFUL.contains(type)) {
            return Pathing.Ground.HARMFUL;
        }
        if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
            return Pathing.Ground.WATER;
        }
        return block.isPassable() ? Pathing.Ground.OPEN : Pathing.Ground.SOLID;
    }
}
