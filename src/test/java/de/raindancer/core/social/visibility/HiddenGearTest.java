package de.raindancer.core.social.visibility;

import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HiddenGearTest {

    @Test
    void everythingAPlayerWearsOrHoldsIsHidden() {
        assertEquals(Set.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET), HiddenGear.SLOTS);
    }
}
