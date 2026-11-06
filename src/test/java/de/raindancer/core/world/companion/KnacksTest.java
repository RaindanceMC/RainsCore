package de.raindancer.core.world.companion;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("a companion's knacks: lifesteal, toughness, thorns, auras, fetching and spotting")
class KnacksTest {

    @Test
    @DisplayName("none unless given, and kept within sense")
    void spec() {
        CompanionSpec plain = CompanionSpec.of(EntityType.WOLF);
        assertThat(plain.lifesteal()).isZero();
        assertThat(plain.toughness()).isZero();
        assertThat(plain.thorns()).isZero();
        assertThat(plain.auras()).isEmpty();
        assertThat(plain.collectRadius()).isZero();
        assertThat(plain.spotRadius()).isZero();
        CompanionSpec odd = plain.lifesteal(3).toughness(1).thorns(-1).collectRadius(99).spotRadius(-4)
                .auras(Map.of("speed", 1));
        assertThat(odd.lifesteal()).isEqualTo(1);
        assertThat(odd.toughness()).isEqualTo(0.8);
        assertThat(odd.thorns()).isZero();
        assertThat(odd.collectRadius()).isEqualTo(16);
        assertThat(odd.spotRadius()).isZero();
        assertThat(odd.auras()).containsEntry("speed", 1);
        assertThat(odd.silent(true).auras()).as("kept by every with").containsEntry("speed", 1);
    }

    @Test
    @DisplayName("toughness takes its share off a blow, lifesteal and thorns give theirs back")
    void shares() {
        assertThat(Knacks.taken(10, 0.25)).isCloseTo(7.5, within(1e-9));
        assertThat(Knacks.taken(10, 0)).isEqualTo(10);
        assertThat(Knacks.share(8, 0.25)).isEqualTo(2);
        assertThat(Knacks.share(8, 0)).isZero();
    }

    @Test
    @DisplayName("fetching takes only what is free for its owner to have")
    void fetching() {
        Knacks.Loot fine = new Knacks.Loot(40, true, true, false, true);
        assertThat(Knacks.fetches(fine)).isTrue();
        assertThat(Knacks.fetches(new Knacks.Loot(5, true, true, false, true))).as("just fell, let it land").isFalse();
        assertThat(Knacks.fetches(new Knacks.Loot(40, false, true, false, true))).as("held for somebody else").isFalse();
        assertThat(Knacks.fetches(new Knacks.Loot(40, true, false, false, true))).as("thrown by somebody else").isFalse();
        assertThat(Knacks.fetches(new Knacks.Loot(40, true, true, true, true))).as("another player stands by it").isFalse();
        assertThat(Knacks.fetches(new Knacks.Loot(40, true, true, false, false))).as("a claim says no").isFalse();
    }

    @Test
    @DisplayName("auras and fetching and spotting take turns, not every tick")
    void paced() {
        assertThat(Knacks.due(60, Knacks.AURA_EVERY)).isTrue();
        assertThat(Knacks.due(61, Knacks.AURA_EVERY)).isFalse();
        assertThat((long) Knacks.AURA_LASTS).isGreaterThan(Knacks.AURA_EVERY);
    }
}
