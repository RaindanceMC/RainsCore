package de.raindancer.core.ui.cosmetic;

import de.raindancer.core.ui.menu.Menu;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CosmeticDoorsTest {

    @AfterEach
    void forget() {
        CosmeticDoors.clear();
    }

    private static CosmeticDoor door(String id) {
        return new CosmeticDoor() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public ItemStack icon(Player viewer) {
                return null;
            }

            @Override
            public Optional<String> locked(Player viewer) {
                return Optional.empty();
            }

            @Override
            public void open(Player viewer, Menu parent) {
            }
        };
    }

    @Test
    @DisplayName("doors come in id order, each id once; retracted, a door is gone")
    void doors() {
        CosmeticDoor rods = door("fishing:rod-skins");
        CosmeticDoors.provide(null, door("pets:pets"));
        CosmeticDoors.provide(null, rods);
        CosmeticDoors.provide(null, door("fishing:rod-skins"));
        assertThat(CosmeticDoors.doors()).extracting(CosmeticDoor::id).containsExactly("fishing:rod-skins", "pets:pets");
        CosmeticDoors.retract(rods);
        assertThat(CosmeticDoors.doors()).extracting(CosmeticDoor::id).containsExactly("pets:pets");
    }

    @Test
    @DisplayName("whether a cosmetics page shows the doors: only while one says so")
    void hosted() {
        assertThat(CosmeticDoors.hosted()).isFalse();
        Object page = new Object();
        CosmeticDoors.host(page);
        assertThat(CosmeticDoors.hosted()).isTrue();
        CosmeticDoors.unhost(page);
        assertThat(CosmeticDoors.hosted()).isFalse();
    }
}
