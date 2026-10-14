package de.raindancer.core.content.enchant;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EnchantUnlocksTest {

    private static final UUID ADA = UUID.nameUUIDFromBytes("ada".getBytes());
    private final EnchantUnlocks.Source guilds = (player, enchantment) -> enchantment.equals(RainsEnchants.TELEKINESIS);

    @AfterEach
    void tearDown() {
        EnchantUnlocks.retract(guilds);
    }

    @Test
    @DisplayName("nobody to ask means locked: the enchantments are earned, never free")
    void lockedByDefault() {
        assertThat(EnchantUnlocks.isUnlocked(ADA, RainsEnchants.TELEKINESIS)).isFalse();
        assertThat(EnchantUnlocks.isAnswered()).isFalse();
    }

    @Test
    @DisplayName("what the keeper says, once it has said it; taken back with it")
    void asks() {
        EnchantUnlocks.provide(mock(Plugin.class), guilds);
        assertThat(EnchantUnlocks.isAnswered()).isTrue();
        assertThat(EnchantUnlocks.isUnlocked(ADA, RainsEnchants.TELEKINESIS)).isTrue();
        assertThat(EnchantUnlocks.isUnlocked(ADA, RainsEnchants.LAUNCH)).isFalse();

        EnchantUnlocks.retract(guilds);
        assertThat(EnchantUnlocks.isUnlocked(ADA, RainsEnchants.TELEKINESIS)).isFalse();
    }

    @Test
    @DisplayName("a keeper that throws unlocks nothing")
    void broken() {
        EnchantUnlocks.Source broken = (player, enchantment) -> {
            throw new IllegalStateException("database gone");
        };
        EnchantUnlocks.provide(mock(Plugin.class), broken);
        try {
            assertThat(EnchantUnlocks.isUnlocked(ADA, RainsEnchants.TELEKINESIS)).isFalse();
        } finally {
            EnchantUnlocks.retract(broken);
        }
    }
}
