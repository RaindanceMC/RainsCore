package de.raindancer.core.content.enchant;

import org.bukkit.inventory.EquipmentSlotGroup;

import java.util.function.Predicate;

/** What an enchantment goes on, and the slot it works from. Matched by item key so nothing needs a server. */
public enum Gear {
    VEIN_TOOLS(EquipmentSlotGroup.MAINHAND, path -> path.endsWith("_pickaxe") || path.endsWith("_axe")
            || path.endsWith("_shovel")),
    HELMET(EquipmentSlotGroup.HEAD, path -> path.endsWith("_helmet")),
    CHESTPLATE(EquipmentSlotGroup.CHEST, path -> path.endsWith("_chestplate")),
    BOOTS(EquipmentSlotGroup.FEET, path -> path.endsWith("_boots"));

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
