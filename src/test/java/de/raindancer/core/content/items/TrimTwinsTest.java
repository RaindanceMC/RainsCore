package de.raindancer.core.content.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which trim recipes get a twin, and under what name. Building the twin needs the server's registries (a
 * {@code TrimPattern} cannot exist without them); that it really trims is the e2e scenarios'.
 */
class TrimTwinsTest {

    private static final NamespacedKey COAST =
            NamespacedKey.minecraft("coast_armor_trim_smithing_template_smithing_trim");

    @Test
    @DisplayName("a trim recipe gets a twin named after it, under the twin's own prefix")
    void named() {
        assertThat(TrimTwins.twinKey(COAST, "rainselytra", "elytra", true, false)).contains(
                new NamespacedKey("rainselytra", "elytra/minecraft/coast_armor_trim_smithing_template_smithing_trim"));
        assertThat(TrimTwins.twinKey(COAST, "rainshorses", "horse", true, false)).contains(
                new NamespacedKey("rainshorses", "horse/minecraft/coast_armor_trim_smithing_template_smithing_trim"));
        assertThat(TrimTwins.twinKey(new NamespacedKey("mypack", "trims/star"), "rainselytra", "elytra", true, false))
                .contains(new NamespacedKey("rainselytra", "elytra/mypack/trims/star"));
    }

    @Test
    @DisplayName("our own twins, recipes whose base takes these already and trims without a pattern get none")
    void skipped() {
        assertThat(TrimTwins.twinKey(new NamespacedKey("rainselytra", "elytra/minecraft/x"), "rainselytra", "elytra",
                true, false)).as("a twin of a twin, on every datapack reload").isEmpty();
        assertThat(TrimTwins.twinKey(COAST, "rainselytra", "elytra", true, true)).as("a datapack already did it").isEmpty();
        assertThat(TrimTwins.twinKey(COAST, "rainselytra", "elytra", false, false)).as("nothing to put on").isEmpty();
    }

    @Test
    @DisplayName("the bases are kept as given, sorted for the recipe")
    void bases() {
        TrimTwins twins = new TrimTwins(null, "rainshorses", "horse",
                Set.of(Material.IRON_HORSE_ARMOR, Material.DIAMOND_HORSE_ARMOR));
        assertThat(twins.materials()).containsExactlyInAnyOrder(Material.IRON_HORSE_ARMOR, Material.DIAMOND_HORSE_ARMOR);
        assertThat(twins.prefix()).isEqualTo("horse");
    }
}
