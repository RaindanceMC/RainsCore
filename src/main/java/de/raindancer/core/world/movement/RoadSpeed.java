package de.raindancer.core.world.movement;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** The arithmetic of {@link MountSettings}, apart from any entity. */
public final class RoadSpeed {

    /** A horse's movement-speed attribute times this is about its blocks a second, ridden on flat ground. */
    private static final double BLOCKS_PER_SECOND_PER_POINT = 43.17;
    /** A dirt path is a sixteenth lower than a full block; looking this far under the hooves finds either. */
    private static final double UNDER_HOOVES = 0.0625;

    private RoadSpeed() {
    }

    public static Set<Material> roads(String list) {
        Set<Material> roads = EnumSet.noneOf(Material.class);
        if (list == null) {
            return roads;
        }
        for (String name : list.split("[\\s,;]+")) {
            String plain = name.strip().toUpperCase(Locale.ROOT).replaceFirst("^MINECRAFT:", "");
            if (plain.isEmpty()) {
                continue;
            }
            Material material = Material.matchMaterial(plain);
            if (material != null && material.isBlock() && !material.isAir()) {
                roads.add(material);
            }
        }
        return roads;
    }

    public static boolean isOff(int roadPercent, int offRoadPercent) {
        return roadPercent == 100 && offRoadPercent == 100;
    }

    /** The factor on the mount's own speed while it stands on {@code under}. */
    public static double multiplier(Material under, Set<Material> roads, int roadPercent, int offRoadPercent) {
        return (under != null && roads.contains(under) ? roadPercent : offRoadPercent) / 100.0;
    }

    /** The block the hooves stand on, for feet at height {@code y}. */
    public static int standingBlockY(double y) {
        return (int) Math.floor(y - UNDER_HOOVES);
    }

    public static double blocksPerSecond(double movementSpeed) {
        return movementSpeed * BLOCKS_PER_SECOND_PER_POINT;
    }
}
