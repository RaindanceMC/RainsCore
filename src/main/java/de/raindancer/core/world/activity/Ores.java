package de.raindancer.core.world.activity;

import org.bukkit.Material;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Which blocks count as ore and as a harvest, and how rare each ore is. */
public final class Ores {

    private static final Map<Material, String> ORES = new EnumMap<>(Material.class);
    private static final Map<String, Integer> WEIGHT = Map.ofEntries(
            Map.entry("coal", 1), Map.entry("copper", 1), Map.entry("iron", 2), Map.entry("gold", 3),
            Map.entry("redstone", 1), Map.entry("lapis", 2), Map.entry("diamond", 8), Map.entry("emerald", 10),
            Map.entry("nether-gold", 1), Map.entry("quartz", 1), Map.entry("ancient-debris", 15));

    static {
        for (String ore : List.of("coal", "copper", "iron", "gold", "redstone", "lapis", "diamond", "emerald")) {
            ORES.put(Material.valueOf(ore.toUpperCase(Locale.ROOT) + "_ORE"), ore);
            ORES.put(Material.valueOf("DEEPSLATE_" + ore.toUpperCase(Locale.ROOT) + "_ORE"), ore);
        }
        ORES.put(Material.NETHER_GOLD_ORE, "nether-gold");
        ORES.put(Material.NETHER_QUARTZ_ORE, "quartz");
        ORES.put(Material.ANCIENT_DEBRIS, "ancient-debris");
    }

    /** Crops that count when fully grown, whoever planted them. */
    public static final Set<Material> AGED_CROPS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS, Material.NETHER_WART, Material.COCOA, Material.TORCHFLOWER_CROP, Material.PITCHER_CROP);

    /** Crops that are a whole block, and only count when the world grew them. */
    public static final Set<Material> GROWN_BLOCKS = EnumSet.of(Material.MELON, Material.PUMPKIN);

    private Ores() {
    }

    /** The ore group a block belongs to — "iron" for both iron ores — or empty for anything else. */
    public static Optional<String> oreOf(Material block) {
        return Optional.ofNullable(ORES.get(block));
    }

    public static Set<Material> ores() {
        return ORES.keySet();
    }

    /** How rare an ore group is: coal 1 … ancient debris 15; 1 for a group it does not know. */
    public static int weight(String group) {
        return WEIGHT.getOrDefault(group, 1);
    }

    /** Every block whose "did a player put this here?" matters. */
    public static Set<Material> watched() {
        Set<Material> all = EnumSet.copyOf(ORES.keySet());
        all.addAll(GROWN_BLOCKS);
        return all;
    }
}
