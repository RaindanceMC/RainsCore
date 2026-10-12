package de.raindancer.core.content.enchant;

/**
 * The keys of Rain's own enchantments, so a module that only has to <em>ask</em> about one (veintoggle
 * checks the tool) does not depend on the module that defines it. Defined and enforced in the enchants
 * module; absent from the registry when that module is not installed.
 */
public final class RainsEnchants {

    public static final String VEINMINER = "rainsenchants:veinminer";
    public static final String NIGHT_VISION = "rainsenchants:night_vision";
    public static final String FLIGHT = "rainsenchants:flight";
    public static final String DOUBLE_JUMP = "rainsenchants:double_jump";

    private RainsEnchants() {
    }
}
