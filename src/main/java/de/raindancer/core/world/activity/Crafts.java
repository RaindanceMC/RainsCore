package de.raindancer.core.world.activity;

import org.bukkit.Material;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** When crafting counts, and how many times one click crafts. */
public final class Crafts {

    private Crafts() {
    }

    /**
     * Whether a recipe with these ingredients (empty slots as null or air) counts: it needs two kinds of ingredient
     * at least — ingot to block and back, nugget to ingot, logs to planks would otherwise pay for going round in
     * circles.
     */
    public static boolean counts(Collection<Material> ingredients) {
        Set<Material> kinds = new HashSet<>();
        for (Material each : ingredients) {
            if (each != null && !each.isAir()) {
                kinds.add(each);
            }
        }
        return kinds.size() >= 2;
    }

    /** How often a shift-click crafts: as often as the scarcest ingredient stack allows. */
    public static int times(Collection<Integer> stackSizes) {
        return stackSizes.stream().mapToInt(Integer::intValue).filter(size -> size > 0).min().orElse(0);
    }
}
