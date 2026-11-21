package de.raindancer.core.world.build;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a player has to carry to put a block up, from its block state: a wall torch is a torch, a double slab two
 * slabs, a door's upper half nothing (the lower one already paid for it), wheat a seed, a potted sapling a pot and the
 * sapling.
 *
 * <p>Item ids as strings, no server needed. Whether the item exists on this server is the caller's question —
 * {@code Material.matchMaterial} — since a block from a newer version than the server has no item here either.
 */
public final class BuildBill {

    /** {@code count} of the item {@code item} (a namespaced id). */
    public record Cost(String item, int count) {
    }

    /** Placed with another item than their own. */
    private static final Map<String, String> PLACED_WITH = Map.ofEntries(
            Map.entry("redstone_wire", "redstone"),
            Map.entry("tripwire", "string"),
            Map.entry("wheat", "wheat_seeds"),
            Map.entry("carrots", "carrot"),
            Map.entry("potatoes", "potato"),
            Map.entry("beetroots", "beetroot_seeds"),
            Map.entry("melon_stem", "melon_seeds"),
            Map.entry("attached_melon_stem", "melon_seeds"),
            Map.entry("pumpkin_stem", "pumpkin_seeds"),
            Map.entry("attached_pumpkin_stem", "pumpkin_seeds"),
            Map.entry("cocoa", "cocoa_beans"),
            Map.entry("sweet_berry_bush", "sweet_berries"),
            Map.entry("cave_vines", "glow_berries"),
            Map.entry("cave_vines_plant", "glow_berries"),
            Map.entry("kelp_plant", "kelp"),
            Map.entry("twisting_vines_plant", "twisting_vines"),
            Map.entry("weeping_vines_plant", "weeping_vines"),
            Map.entry("bamboo_sapling", "bamboo"),
            Map.entry("tall_seagrass", "seagrass"),
            Map.entry("farmland", "dirt"),
            Map.entry("dirt_path", "dirt"),
            Map.entry("big_dripleaf_stem", "big_dripleaf"),
            Map.entry("powder_snow", "powder_snow_bucket"),
            Map.entry("torchflower_crop", "torchflower_seeds"),
            Map.entry("pitcher_crop", "pitcher_pod"),
            Map.entry("wall_torch", "torch"),
            Map.entry("lava", "lava_bucket"),
            Map.entry("water", "water_bucket"),
            Map.entry("azalea_bush", "azalea"),
            Map.entry("flowering_azalea_bush", "flowering_azalea"));

    /** Nothing a player can place by hand. */
    private static final Set<String> UNPLACEABLE = Set.of(
            "air", "cave_air", "void_air", "fire", "soul_fire", "nether_portal", "end_portal", "end_gateway",
            "piston_head", "moving_piston", "bubble_column", "frosted_ice", "light", "structure_void", "barrier",
            "jigsaw", "structure_block", "command_block", "chain_command_block", "repeating_command_block",
            "bedrock", "spawner", "trial_spawner", "vault", "budding_amethyst", "reinforced_deepslate",
            "end_portal_frame", "test_block", "test_instance_block");

    /** How many of the item one block holds, by the property that says so. */
    private static final List<String> COUNTED_BY = List.of(
            "candles", "pickles", "eggs", "layers", "flower_amount", "segment_amount");

    private BuildBill() {
    }

    public static List<Cost> costOf(String state) {
        String id = StateMatch.id(state);
        String name = id.substring(id.indexOf(':') + 1);
        String namespace = id.substring(0, id.indexOf(':') + 1);
        if (UNPLACEABLE.contains(name)) {
            return List.of();
        }
        Map<String, String> properties = StateMatch.properties(state);
        if ("upper".equals(properties.get("half")) || "head".equals(properties.get("part"))) {
            return List.of();
        }
        if ((name.equals("water") || name.equals("lava")) && !"0".equals(properties.getOrDefault("level", "0"))) {
            return List.of();
        }
        if (name.startsWith("potted_")) {
            String plant = name.substring("potted_".length());
            return List.of(new Cost(namespace + "flower_pot", 1),
                    new Cost(namespace + PLACED_WITH.getOrDefault(plant, plant), 1));
        }
        String item = PLACED_WITH.get(name);
        if (item == null) {
            // oak_wall_sign → oak_sign, soul_wall_torch → soul_torch, white_wall_banner → white_banner.
            item = name.contains("_wall_") ? name.replace("_wall_", "_") : name;
        }
        int count = 1;
        if ("double".equals(properties.get("type")) && name.endsWith("_slab")) {
            count = 2;
        }
        for (String counting : COUNTED_BY) {
            String value = properties.get(counting);
            if (value != null) {
                try {
                    count = Math.max(1, Integer.parseInt(value));
                } catch (NumberFormatException ignored) {
                    count = 1;
                }
            }
        }
        return List.of(new Cost(namespace + item, count));
    }

    /** Every block's cost added up per item, the most needed first (then by name, so the order is stable). */
    public static Map<String, Integer> of(Collection<String> states) {
        Map<String, Integer> totals = new HashMap<>();
        Map<String, List<Cost>> seen = new HashMap<>();
        for (String state : states) {
            for (Cost cost : seen.computeIfAbsent(state, BuildBill::costOf)) {
                totals.merge(cost.item(), cost.count(), Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(totals.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : sorted) {
            ordered.put(entry.getKey(), entry.getValue());
        }
        return ordered;
    }

    /** The one item that stands for a block in a menu — its first cost — if it costs anything. */
    public static Optional<String> iconOf(String state) {
        List<Cost> costs = costOf(state);
        return costs.isEmpty() ? Optional.empty() : Optional.of(costs.getLast().item());
    }
}
