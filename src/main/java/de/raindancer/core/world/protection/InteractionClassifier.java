package de.raindancer.core.world.protection;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Lectern;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;

/**
 * Maps a right-clicked block or entity to the {@link LandAction} it needs.
 * <p>
 * Uses block data interfaces and vanilla tags rather than hardcoded material lists, so new blocks in
 * future Minecraft versions are classified correctly without a code change.
 */
public final class InteractionClassifier {

    private InteractionClassifier() {
    }

    /** The permission needed to right-click this block, or {@code null} when it is harmless. */
    public static LandAction forBlock(Block block) {
        Material type = block.getType();

        // Containers first: a chest is a chest even if it is also openable.
        BlockState state = block.getState(false);
        if (state instanceof Container || state instanceof Lectern) {
            return LandAction.CONTAINERS;
        }
        if (Tag.SHULKER_BOXES.isTagged(type)) {
            return LandAction.CONTAINERS;
        }

        if (Tag.BEDS.isTagged(type) || type == Material.RESPAWN_ANCHOR) {
            return LandAction.BEDS;
        }
        if (Tag.BUTTONS.isTagged(type) || Tag.PRESSURE_PLATES.isTagged(type)
                || type == Material.LEVER || type == Material.DAYLIGHT_DETECTOR
                || type == Material.REPEATER || type == Material.COMPARATOR
                || type == Material.NOTE_BLOCK || type == Material.REDSTONE_WIRE) {
            return LandAction.REDSTONE;
        }

        BlockData data = block.getBlockData();
        if (data instanceof Openable && !(data instanceof Switch)) {
            return LandAction.DOORS;
        }

        if (isWorkstation(type)) {
            return LandAction.WORKSTATIONS;
        }
        if (Tag.CAULDRONS.isTagged(type) || Tag.FLOWER_POTS.isTagged(type)
                || Tag.CANDLES.isTagged(type) || Tag.CANDLE_CAKES.isTagged(type)
                || Tag.ALL_SIGNS.isTagged(type) || type == Material.CAKE
                || type == Material.COMPOSTER || type == Material.JUKEBOX
                || type == Material.BEEHIVE || type == Material.BEE_NEST
                || type == Material.SWEET_BERRY_BUSH || type == Material.CAVE_VINES
                || type == Material.CAVE_VINES_PLANT || type == Material.DECORATED_POT
                || type == Material.CHISELED_BOOKSHELF) {
            return LandAction.BUILD;
        }
        if (Tag.CROPS.isTagged(type) || type == Material.FARMLAND) {
            return LandAction.FARMLAND;
        }
        return null;
    }

    private static boolean isWorkstation(Material type) {
        return switch (type) {
            case CRAFTING_TABLE, CRAFTER, ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL, ENCHANTING_TABLE,
                 GRINDSTONE, SMITHING_TABLE, STONECUTTER, LOOM, CARTOGRAPHY_TABLE, FLETCHING_TABLE,
                 ENDER_CHEST, BELL -> true;
            default -> false;
        };
    }

    /** The permission needed to interact with this entity, or {@code null} when it is harmless. */
    public static LandAction forEntityInteract(Entity entity) {
        if (entity instanceof ItemFrame || entity instanceof ArmorStand) {
            return LandAction.ITEM_FRAMES;
        }
        if (entity instanceof Villager || entity instanceof WanderingTrader) {
            return LandAction.TRADE;
        }
        if (entity instanceof Vehicle) {
            return LandAction.VEHICLES;
        }
        if (entity instanceof Animals) {
            return LandAction.ANIMALS;
        }
        return null;
    }

    /** The permission needed to damage this entity, or {@code null} when unprotected. */
    public static LandAction forEntityDamage(Entity entity) {
        if (entity instanceof ItemFrame || entity instanceof ArmorStand) {
            return LandAction.ITEM_FRAMES;
        }
        if (entity instanceof Vehicle) {
            return LandAction.VEHICLES;
        }
        if (entity instanceof Animals || entity instanceof Villager || entity instanceof WanderingTrader) {
            return LandAction.DAMAGE_ANIMALS;
        }
        return null;
    }

    /**
     * The flag deciding whether a player may harm this creature, or {@code null} when it is not a creature.
     *
     * <p>Two flags rather than one because the reasons differ — see {@link LandFlag#HIT_MOBS} and
     * {@link LandFlag#HIT_MONSTERS}. Players are neither: they go through PvP.
     */
    public static LandFlag forCreatureDamage(Entity entity) {
        if (entity instanceof Player || !(entity instanceof LivingEntity)) {
            return null;
        }
        return isHostile(entity) ? LandFlag.HIT_MONSTERS : LandFlag.HIT_MOBS;
    }

    /**
     * Whether a creature is one the world sent to do harm.
     *
     * <p>Lives here rather than in a listener because three of them ask it — spawning, entry and now hitting —
     * and a copy per listener is how "is a hoglin hostile" ends up answered differently in each.
     *
     * <p>The type checks beside {@code Monster} are not redundant: a ghast, a phantom, a shulker, a slime and both
     * bosses are hostile without implementing that interface.
     *
     * <p>{@link Enemy} and the {@code MONSTER} spawn category are vanilla's own answer, so a mob added later is
     * covered without a release here. The category is what catches the camel husk: the API calls it a camel,
     * vanilla spawns it as a monster.
     */
    public static boolean isHostile(Entity entity) {
        return entity instanceof Monster || entity instanceof Enemy || entity instanceof Slime
                || entity.getSpawnCategory() == org.bukkit.entity.SpawnCategory.MONSTER
                || entity.getType() == EntityType.GHAST
                || entity.getType() == EntityType.PHANTOM
                || entity.getType() == EntityType.HOGLIN
                || entity.getType() == EntityType.SHULKER
                || entity.getType() == EntityType.ENDER_DRAGON
                || entity.getType() == EntityType.WITHER
                || entity.getType() == EntityType.SLIME
                || entity.getType() == EntityType.MAGMA_CUBE;
    }
}
