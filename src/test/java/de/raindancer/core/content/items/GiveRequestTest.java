package de.raindancer.core.content.items;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class GiveRequestTest {

    private static final Predicate<String> OURS = Set.of("fishing:rod-void", "fishing:bait-voidlure")::contains;

    @Test
    @DisplayName("one of our items: who, what and how many; one when no amount is said")
    void ours() {
        assertThat(GiveRequest.parse(new String[]{"Ada", "fishing:rod-void"}, OURS))
                .isEqualTo(new GiveRequest.Ours("Ada", "fishing:rod-void", 1));
        assertThat(GiveRequest.parse(new String[]{"@a", "FISHING:Bait-Voidlure", "16"}, OURS))
                .isEqualTo(new GiveRequest.Ours("@a", "fishing:bait-voidlure", 16));
    }

    @Test
    @DisplayName("anything we do not know goes to vanilla as typed, components and all")
    void vanilla() {
        assertThat(GiveRequest.parse(new String[]{"Ada", "diamond", "3"}, OURS))
                .isEqualTo(new GiveRequest.Vanilla("minecraft:give Ada diamond 3"));
        assertThat(GiveRequest.parse(new String[]{"@p", "minecraft:stone"}, OURS))
                .isEqualTo(new GiveRequest.Vanilla("minecraft:give @p minecraft:stone"));
        assertThat(GiveRequest.parse(new String[]{"Ada", "diamond_sword[enchantments={sharpness:5}]"}, OURS))
                .isEqualTo(new GiveRequest.Vanilla("minecraft:give Ada diamond_sword[enchantments={sharpness:5}]"));
        assertThat(GiveRequest.parse(new String[]{"Ada", "fishing:no-such-thing"}, OURS))
                .isEqualTo(new GiveRequest.Vanilla("minecraft:give Ada fishing:no-such-thing"));
        assertThat(GiveRequest.parse(new String[0], OURS)).isEqualTo(new GiveRequest.Vanilla("minecraft:give"));
    }

    @Test
    @DisplayName("a bad amount for one of ours is refused with why, never quietly one")
    void badAmount() {
        assertThat(GiveRequest.parse(new String[]{"Ada", "fishing:rod-void", "lots"}, OURS))
                .isInstanceOf(GiveRequest.Refused.class);
        assertThat(GiveRequest.parse(new String[]{"Ada", "fishing:rod-void", "0"}, OURS))
                .isInstanceOf(GiveRequest.Refused.class);
        assertThat(GiveRequest.parse(new String[]{"Ada", "fishing:rod-void", "99999"}, OURS))
                .isInstanceOf(GiveRequest.Refused.class);
        assertThat(GiveRequest.parse(new String[]{"Ada", "fishing:rod-void", "1", "extra"}, OURS))
                .isInstanceOf(GiveRequest.Refused.class);
    }

    @Test
    @DisplayName("what to suggest for the item: ours and vanilla's, matching what is typed so far")
    void suggestions() {
        assertThat(GiveRequest.suggest("fishing:b", java.util.List.of("fishing:rod-void", "fishing:bait-voidlure"),
                java.util.List.of("minecraft:diamond")))
                .containsExactly("fishing:bait-voidlure");
        assertThat(GiveRequest.suggest("dia", java.util.List.of("fishing:rod-void"),
                java.util.List.of("minecraft:diamond", "minecraft:stone")))
                .containsExactly("diamond");
        assertThat(GiveRequest.suggest("minecraft:dia", java.util.List.of(), java.util.List.of("minecraft:diamond")))
                .containsExactly("minecraft:diamond");
        assertThat(GiveRequest.suggest("", java.util.List.of("fishing:rod-void"), java.util.List.of("minecraft:stone")))
                .contains("fishing:rod-void", "stone");
    }
}
