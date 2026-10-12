package de.raindancer.core.world.protection;

import java.util.Locale;
import java.util.Set;

/**
 * The decisions behind a locked world, by name so they are made without a server. Lists of what is allowed rather than of
 * what is not wherever that is the safe side: an item or a block a new version adds is refused until somebody decides.
 */
public final class LockedWorldRules {

    /** What still works for somebody whose crosshair is on a block: what a fight or a meal uses, nothing that changes it. */
    private static final Set<String> USABLE = Set.of(
            "AIR", "BOW", "CROSSBOW", "TRIDENT", "MACE", "SHIELD", "FISHING_ROD", "ENDER_PEARL", "SNOWBALL",
            "WIND_CHARGE", "TOTEM_OF_UNDYING", "MILK_BUCKET", "POTION", "SPLASH_POTION", "LINGERING_POTION",
            "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE", "GOLDEN_CARROT", "APPLE", "BREAD", "COOKED_BEEF",
            "COOKED_PORKCHOP", "COOKED_CHICKEN", "COOKED_MUTTON", "COOKED_RABBIT", "COOKED_COD", "COOKED_SALMON",
            "BAKED_POTATO", "PUMPKIN_PIE", "COOKIE", "MELON_SLICE", "CARROT", "SWEET_BERRIES", "DRIED_KELP",
            "HONEY_BOTTLE", "SUSPICIOUS_STEW", "MUSHROOM_STEW", "RABBIT_STEW", "BEETROOT_SOUP", "CHORUS_FRUIT",
            "FIREWORK_ROCKET", "SPYGLASS", "GOAT_HORN", "COMPASS", "RECOVERY_COMPASS", "CLOCK");

    /** What can tear a world apart and has no place in a locked one — not let in at all, /summon included. */
    private static final Set<String> NEVER = Set.of("WITHER", "ENDER_DRAGON");

    /** Blocks a projectile changes by hitting them. */
    private static final Set<String> FRAGILE = Set.of("DECORATED_POT", "CHORUS_FLOWER", "POINTED_DRIPSTONE", "TNT",
            "BELL", "TARGET");

    private LockedWorldRules() {
    }

    /** Not swords or axes: a right click with either does nothing in a fight, and an axe's strips a log. */
    public static boolean usableAgainstBlocks(String material) {
        String name = material == null ? "AIR" : material.toUpperCase(Locale.ROOT);
        return USABLE.contains(name) || name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
    }

    public static boolean mayExist(String entityType) {
        return entityType == null || !NEVER.contains(entityType.toUpperCase(Locale.ROOT));
    }

    public static boolean fragile(String block) {
        String name = block == null ? "" : block.toUpperCase(Locale.ROOT);
        return FRAGILE.contains(name) || name.endsWith("CANDLE") || name.endsWith("CAMPFIRE");
    }
}
