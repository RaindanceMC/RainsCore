package de.raindancer.core.ui.tree;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SkillPointsTest {

    private final UUID player = UUID.randomUUID();

    private record FixedPool(int level, int total, int spent) implements SkillPoints.Pool {
        @Override
        public int level(UUID p) {
            return level;
        }

        @Override
        public int total(UUID p) {
            return total;
        }

        @Override
        public int spent(UUID p) {
            return spent;
        }
    }

    @AfterEach
    void clear() {
        SkillPoints.clear();
    }

    @Test
    @DisplayName("nothing provided: no points, and a reason")
    void nothingProvided() {
        assertThat(SkillPoints.available()).isFalse();
        assertThat(SkillPoints.left(player)).isZero();
        assertThat(SkillPoints.total(player)).isZero();
        assertThat(SkillPoints.level(player)).isZero();
        assertThat(SkillPoints.unavailableReason()).isNotBlank();
    }

    @Test
    @DisplayName("left is total minus the pool's own spending")
    void aloneInThePool() {
        SkillPoints.provide(null, new FixedPool(12, 10, 4));
        assertThat(SkillPoints.available()).isTrue();
        assertThat(SkillPoints.level(player)).isEqualTo(12);
        assertThat(SkillPoints.left(player)).isEqualTo(6);
    }

    @Test
    @DisplayName("every spender is subtracted, so a point cannot be spent twice")
    void spendersCount() {
        SkillPoints.provide(null, new FixedPool(20, 20, 5));
        SkillPoints.spender(null, "enchants", p -> 6);
        SkillPoints.spender(null, "pets", p -> 2);
        assertThat(SkillPoints.spentElsewhere(player)).isEqualTo(8);
        assertThat(SkillPoints.left(player)).isEqualTo(7);
    }

    @Test
    @DisplayName("left never drops below zero, a spender with a bad answer counts as nothing")
    void clamped() {
        SkillPoints.provide(null, new FixedPool(3, 3, 3));
        SkillPoints.spender(null, "a", p -> 5);
        SkillPoints.spender(null, "b", p -> -4);
        assertThat(SkillPoints.spentElsewhere(player)).isEqualTo(5);
        assertThat(SkillPoints.left(player)).isZero();
    }

    @Test
    @DisplayName("a spender registered twice under one id is replaced, not counted twice")
    void replaced() {
        SkillPoints.provide(null, new FixedPool(10, 10, 0));
        SkillPoints.spender(null, "enchants", p -> 3);
        SkillPoints.spender(null, "enchants", p -> 4);
        assertThat(SkillPoints.spentElsewhere(player)).isEqualTo(4);
    }

    @Test
    @DisplayName("a spender that throws counts as nothing")
    void throwing() {
        SkillPoints.provide(null, new FixedPool(10, 10, 0));
        SkillPoints.spender(null, "broken", p -> {
            throw new IllegalStateException("boom");
        });
        assertThat(SkillPoints.left(player)).isEqualTo(10);
    }

    @Test
    @DisplayName("unregister takes the spender or the pool away again")
    void unregister() {
        SkillPoints.provide(null, new FixedPool(10, 10, 0));
        SkillPoints.spender(null, "enchants", p -> 3);
        assertThat(SkillPoints.removeSpender("enchants")).isTrue();
        assertThat(SkillPoints.left(player)).isEqualTo(10);
        SkillPoints.withdraw();
        assertThat(SkillPoints.available()).isFalse();
    }
}
