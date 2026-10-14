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

    private RainsEnchants() {
    }
}
