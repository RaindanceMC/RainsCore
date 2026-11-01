package de.raindancer.core.content.enchant;

/**
 * The keys of Rain's own enchantments, so a module that only has to <em>ask</em> about one (veintoggle
 * checks the tool) does not depend on the module that defines it. Defined and enforced in the enchants
 * module (unless noted); absent from the registry when that module is not installed.
 */
public final class RainsEnchants {

    public static final String VEINMINER = "rainsenchants:veinminer";
    public static final String NIGHT_VISION = "rainsenchants:night_vision";
    public static final String FLIGHT = "rainsenchants:flight";
    public static final String DOUBLE_JUMP = "rainsenchants:double_jump";
    /** Enforced by the economy module, which knows the prices. */
    public static final String AUTO_SELL = "rainsenchants:auto_sell";
    public static final String BOOST = "rainsenchants:boost";
    public static final String LAUNCH = "rainsenchants:launch";
    public static final String SWIFTNESS = "rainsenchants:swiftness";
    public static final String HASTE = "rainsenchants:haste";
    public static final String SOULBOUND = "rainsenchants:soulbound";
    public static final String WISDOM = "rainsenchants:wisdom";
    public static final String EXCAVATOR = "rainsenchants:excavator";
    public static final String HARVESTER = "rainsenchants:harvester";
    public static final String SMELTING = "rainsenchants:smelting";
    public static final String TELEKINESIS = "rainsenchants:telekinesis";
    public static final String CHEST_LINK = "rainsenchants:chest_link";
    public static final String HEAT_SEEKING = "rainsenchants:heat_seeking";
    public static final String THOR = "rainsenchants:thor";
    public static final String VOLLEY = "rainsenchants:volley";
    public static final String LIFESTEAL = "rainsenchants:lifesteal";
    public static final String EXECUTIONER = "rainsenchants:executioner";
    public static final String BLEED = "rainsenchants:bleed";
    public static final String VENOM = "rainsenchants:venom";
    public static final String CLEAVE = "rainsenchants:cleave";
    public static final String ARMOR_PIERCE = "rainsenchants:armor_pierce";
    public static final String BERSERK = "rainsenchants:berserk";
    public static final String OVERPOWER = "rainsenchants:overpower";
    public static final String UNDYING = "rainsenchants:undying";
    public static final String FUSION = "rainsenchants:fusion";
    public static final String AUTOPILOT = "rainsenchants:autopilot";
    /** Wears more slowly; on top of Unbreaking, not instead of it. */
    public static final String REINFORCED = "rainsenchants:reinforced";
    /** Experience goes straight to the player, as Telekinesis does with drops. */
    public static final String XP_SIPHON = "rainsenchants:xp_siphon";

    // Fishing: given out as shards like every other, and read by RainsFishing off the rod a fish is caught with.
    /** Fish bite sooner. */
    public static final String DEEP_BITE = "rainsenchants:deep_bite";
    /** Sea chests bite more often. */
    public static final String TREASURE_HUNTER = "rainsenchants:treasure_hunter";
    /** Sometimes two fish come up at once. */
    public static final String TWIN_CATCH = "rainsenchants:twin_catch";
    /** Rarer fish and chests come up more often. */
    public static final String MYTHIC_LURE = "rainsenchants:mythic_lure";

    private RainsEnchants() {
    }
}
