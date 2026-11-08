package de.raindancer.core.content.enchant;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EnchantShardsOfferedTest {

    private final AtomicBoolean world = new AtomicBoolean(true);
    private final BooleanSupplier enchants = world::get;

    @AfterEach
    void tearDown() {
        EnchantUnlocks.retractShards(enchants);
    }

    @Test
    @DisplayName("nobody says: shards count as offered, so servers on an older enchants plugin keep their menus")
    void offeredWhenUnknown() {
        assertThat(EnchantUnlocks.shardsOffered()).isTrue();
    }

    @Test
    @DisplayName("the enchants plugin decides, live; taken back means unknown again")
    void followsTheSource() {
        EnchantUnlocks.shardsFrom(mock(Plugin.class), enchants);
        assertThat(EnchantUnlocks.shardsOffered()).isTrue();
        world.set(false);
        assertThat(EnchantUnlocks.shardsOffered()).isFalse();
        EnchantUnlocks.retractShards(enchants);
        assertThat(EnchantUnlocks.shardsOffered()).isTrue();
    }

    @Test
    @DisplayName("a source that throws is ignored rather than hiding everything")
    void brokenSource() {
        BooleanSupplier broken = () -> { throw new IllegalStateException("boom"); };
        EnchantUnlocks.shardsFrom(mock(Plugin.class), broken);
        try {
            assertThat(EnchantUnlocks.shardsOffered()).isTrue();
        } finally {
            EnchantUnlocks.retractShards(broken);
        }
    }
}
