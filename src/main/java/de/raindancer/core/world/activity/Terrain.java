package de.raindancer.core.world.activity;

import org.bukkit.Material;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The world's own blocks that count when broken, and as what: raw stone {@link ActivityKind#DIG}, dirt, sand and
 * gravel {@link ActivityKind#EXCAVATE}, logs {@link ActivityKind#CHOP}, mushrooms and flowers
 * {@link ActivityKind#FORAGE}. Only what the world made — never a block a player placed — and never cobblestone,
 * which a generator makes without end.
 */
public final class Terrain {

    private static final Map<Material, ActivityKind> KINDS = new EnumMap<>(Material.class);

    static {
        for (Material dig : EnumSet.of(Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.GRANITE,
                Material.DIORITE, Material.ANDESITE, Material.CALCITE, Material.NETHERRACK, Material.END_STONE,
                Material.BLACKSTONE, Material.BASALT, Material.SANDSTONE, Material.RED_SANDSTONE, Material.DRIPSTONE_BLOCK,
                Material.SMOOTH_BASALT)) {
            KINDS.put(dig, ActivityKind.DIG);
        }
        for (Material soft : EnumSet.of(Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.PODZOL,
                Material.MYCELIUM, Material.ROOTED_DIRT, Material.MUD, Material.SAND, Material.RED_SAND,
                Material.GRAVEL, Material.CLAY, Material.SOUL_SAND, Material.SOUL_SOIL, Material.SUSPICIOUS_SAND,
                Material.SUSPICIOUS_GRAVEL, Material.SNOW_BLOCK)) {
            KINDS.put(soft, ActivityKind.EXCAVATE);
        }
        for (Material material : Material.values()) {
            String name = material.name();
            if (material.isLegacy() || name.startsWith("STRIPPED_")) {
                continue;
            }
            if (name.endsWith("_LOG") || name.endsWith("_STEM")) {
                KINDS.put(material, ActivityKind.CHOP);
            }
        }
        for (Material forage : EnumSet.of(Material.RED_MUSHROOM, Material.BROWN_MUSHROOM, Material.CRIMSON_FUNGUS,
                Material.WARPED_FUNGUS, Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM,
                Material.AZURE_BLUET, Material.RED_TULIP, Material.ORANGE_TULIP, Material.WHITE_TULIP,
                Material.PINK_TULIP, Material.OXEYE_DAISY, Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY,
                Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY, Material.PINK_PETALS,
                Material.SPORE_BLOSSOM, Material.GLOW_LICHEN, Material.SEA_PICKLE)) {
            KINDS.put(forage, ActivityKind.FORAGE);
        }
    }

    private Terrain() {
    }

    public static Optional<ActivityKind> kindOf(Material block) {
        return Optional.ofNullable(KINDS.get(block));
    }

    /** Every block here whose "did a player put this here?" matters. */
    public static Set<Material> watched() {
        return KINDS.isEmpty() ? Set.of() : EnumSet.copyOf(KINDS.keySet());
    }

    static String key(Material material) {
        return material.name().toLowerCase(Locale.ROOT);
    }
}
