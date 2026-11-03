package de.raindancer.core.content.items;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GiveCatalogueTest {

    private static final List<String> KEYS = List.of("fishing:rod-void", "fishing:bait-voidlure", "fishing:net",
            "economy:coin", "enchants:shard-deep_bite");
    private static final Map<String, String> NAMES = Map.of("fishing:rod-void", "Void Rod",
            "fishing:bait-voidlure", "Voidlure", "fishing:net", "Fishing Net", "economy:coin", "Coin",
            "enchants:shard-deep_bite", "Deep Bite Shard");

    private static List<String> filter(String namespace, String search) {
        return GiveCatalogue.filter(KEYS, namespace, search, key -> NAMES.getOrDefault(key, key));
    }

    @Test
    @DisplayName("filtered by plugin, then by search over names and keys alike, case and colour-blind — so typing a name finds the key")
    void filtering() {
        assertThat(filter(null, "")).hasSize(5);
        assertThat(filter("fishing", null)).containsExactly("fishing:bait-voidlure", "fishing:net", "fishing:rod-void");
        assertThat(filter(null, "void")).containsExactly("fishing:bait-voidlure", "fishing:rod-void");
        assertThat(filter(null, "deep bite")).containsExactly("enchants:shard-deep_bite");
        assertThat(filter(null, "COIN")).containsExactly("economy:coin");
        assertThat(filter("economy", "void")).isEmpty();
    }

    @Test
    @DisplayName("a display name's markup and colour codes never decide a search")
    void plain() {
        assertThat(GiveCatalogue.plain("<#7ff6ff>Voidlure</#7ff6ff>")).isEqualTo("voidlure");
        assertThat(GiveCatalogue.plain("§bVoid Rod")).isEqualTo("void rod");
    }

    private static final List<String> VANILLA = List.of("minecraft:diamond", "minecraft:diamond_sword",
            "minecraft:stone", "minecraft:fishing_rod");

    private static java.util.Optional<String> meant(String typed) {
        return GiveCatalogue.didYouMean(typed, KEYS, VANILLA, key -> NAMES.getOrDefault(key, key));
    }

    @Test
    @DisplayName("did you mean: a slip in a key, a missing plugin name, a name typed instead of a key, a vanilla typo")
    void didYouMean() {
        assertThat(meant("fishing:rod-viod")).contains("fishing:rod-void");
        assertThat(meant("fishng:rod-void")).contains("fishing:rod-void");
        assertThat(meant("rod-void")).contains("fishing:rod-void");
        assertThat(meant("void rod")).contains("fishing:rod-void");
        assertThat(meant("voidlur")).contains("fishing:bait-voidlure");
        assertThat(meant("diamnd")).contains("diamond");
        assertThat(meant("minecraft:diamnd")).contains("diamond");
        assertThat(meant("xyzzyqq")).isEmpty();
    }

    @Test
    @DisplayName("what is typed with a space after the plugin's colon is the key it obviously is")
    void joined() {
        assertThat(GiveCatalogue.joined("fishing: rod-void 3")).isEqualTo("fishing:rod-void 3");
        assertThat(GiveCatalogue.joined("fishing:rod-void 3")).isEqualTo("fishing:rod-void 3");
        assertThat(GiveCatalogue.joined("diamond_sword[enchantments={sharpness: 5}]"))
                .isEqualTo("diamond_sword[enchantments={sharpness: 5}]");
    }
}
