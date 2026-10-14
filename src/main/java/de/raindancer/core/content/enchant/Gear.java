package de.raindancer.core.content.enchant;

import org.bukkit.inventory.EquipmentSlotGroup;

import java.util.Set;
import java.util.function.Predicate;

/** What an enchantment goes on, and the slot it works from. Matched by item key so nothing needs a server. */
public enum Gear {
    VEIN_TOOLS(EquipmentSlotGroup.MAINHAND, path -> path.endsWith("_pickaxe") || path.endsWith("_axe")
            || path.endsWith("_shovel")),
    HELMET(EquipmentSlotGroup.HEAD, path -> path.endsWith("_helmet")),
    CHESTPLATE(EquipmentSlotGroup.CHEST, path -> path.endsWith("_chestplate")),
    BOOTS(EquipmentSlotGroup.FEET, path -> path.endsWith("_boots")),
    LEGGINGS(EquipmentSlotGroup.LEGS, path -> path.endsWith("_leggings")),
    ELYTRA(EquipmentSlotGroup.CHEST, "elytra"::equals),
    HOE(EquipmentSlotGroup.MAINHAND, path -> path.endsWith("_hoe")),
    /** What digs: pickaxes, axes, shovels and hoes. */
    DIGGING_TOOLS(EquipmentSlotGroup.MAINHAND, Gear::digs),
    /** What digs in stone and earth, where breaking a whole face makes sense. */
    EXCAVATING_TOOLS(EquipmentSlotGroup.MAINHAND, path -> path.endsWith("_pickaxe") || path.endsWith("_shovel")),
    /** Whatever breaks blocks or kills mobs from the main hand. */
    HAND_TOOLS(EquipmentSlotGroup.MAINHAND, Gear::handTool),
    /** What Thor goes on: axes and the mace, thrown like a hammer, and the trident. */
    THUNDER(EquipmentSlotGroup.MAINHAND, path -> path.endsWith("_axe") && !path.endsWith("_pickaxe")
            || "mace".equals(path) || "trident".equals(path)),
    /** What shoots or is thrown: bows, crossbows, tridents. */
    RANGED(EquipmentSlotGroup.MAINHAND, path -> Set.of("bow", "crossbow", "trident").contains(path)),
    /** Anything worn or held that can be enchanted at all — what is worth keeping through a death. */
    ANY_GEAR(EquipmentSlotGroup.ANY, path -> handTool(path) || path.endsWith("_helmet")
            || path.endsWith("_chestplate") || path.endsWith("_leggings") || path.endsWith("_boots")
            || Set.of("elytra", "shield", "fishing_rod", "flint_and_steel", "brush", "carrot_on_a_stick",
            "warped_fungus_on_a_stick").contains(path));

    private static final Set<String> RANGED_AND_ODD = Set.of("bow", "crossbow", "mace", "trident", "shears");

    private static boolean digs(String path) {
        return path.endsWith("_pickaxe") || path.endsWith("_axe") || path.endsWith("_shovel") || path.endsWith("_hoe");
    }

    private static boolean handTool(String path) {
        return digs(path) || path.endsWith("_sword") || RANGED_AND_ODD.contains(path);
    }

    private final EquipmentSlotGroup slot;
    private final Predicate<String> matcher;

    Gear(EquipmentSlotGroup slot, Predicate<String> matcher) {
        this.slot = slot;
        this.matcher = matcher;
    }

    /** @param path an item key without its namespace, {@code iron_pickaxe} */
    public boolean matches(String path) {
        return matcher.test(path);
    }

    public EquipmentSlotGroup slot() {
        return slot;
    }
}
