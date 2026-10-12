package de.raindancer.core.ui.hub;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HubLookTest {

    private static final List<String> SHIPPED = List.of("anticheat", "chained", "chat", "claims", "cosmetics",
            "economy", "essentials", "farmworld", "homes", "hungergames", "invsnap", "jobs", "mannequin",
            "moderation", "names", "pack", "performance", "playerutils", "roles", "rtp", "sends", "speedrun",
            "tpa", "veintoggle", "wallsroads", "warp", "worldgate", "xaeromap", "xpbottle");

    @Test
    @DisplayName("every shipped module has its own icon, and an unknown one still gets a usable look")
    void everyModuleHasALook() {
        for (String id : SHIPPED) {
            HubLook look = HubLook.of(id);
            assertThat(look.icon()).as(id).isNotEqualTo(Material.STONE).matches(Material::isItem);
            assertThat(look.accent()).as(id).matches("#[0-9A-F]{6}");
        }
        HubLook unknown = HubLook.of("something-new");
        assertThat(unknown.icon().isItem()).isTrue();
        assertThat(unknown).isEqualTo(HubLook.of("something-new"));
    }

    @Test
    @DisplayName("ids are matched without caring for dashes or case: farm-world is farmworld")
    void normalised() {
        assertThat(HubLook.of("Farm-World")).isEqualTo(HubLook.of("farmworld"));
    }

    @Test
    @DisplayName("lightening an accent moves it toward white and never past it")
    void lighten() {
        assertThat(HubLook.lighter("#000000", 0.5)).isEqualTo("#808080");
        assertThat(HubLook.lighter("#FF0000", 1.0)).isEqualTo("#FFFFFF");
        assertThat(HubLook.lighter("#123456", 0.0)).isEqualTo("#123456");
    }

    @Test
    @DisplayName("wrapping breaks at spaces, keeps every word, and lets a long word overflow rather than vanish")
    void wrap() {
        assertThat(HubLook.wrap("one two three four", 9)).containsExactly("one two", "three", "four");
        assertThat(HubLook.wrap("supercalifragilistic ok", 8)).containsExactly("supercalifragilistic", "ok");
        assertThat(HubLook.wrap("", 10)).isEmpty();
        assertThat(HubLook.wrap(null, 10)).isEmpty();
    }

    @Test
    @DisplayName("clipping keeps the first lines and marks the cut with an ellipsis; short text is untouched")
    void clip() {
        assertThat(HubLook.clip(List.of("a", "b", "c", "d"), 3)).containsExactly("a", "b", "c…");
        assertThat(HubLook.clip(List.of("a", "b"), 3)).containsExactly("a", "b");
        assertThat(HubLook.clip(List.of(), 3)).isEmpty();
    }

    @Test
    @DisplayName("a command gets an icon from what its name says; anything unrecognised keeps the module's own")
    void commandIcons() {
        assertThat(HubLook.forCommand("home", Material.GOLD_INGOT)).isEqualTo(Material.RED_BED);
        assertThat(HubLook.forCommand("home set", Material.GOLD_INGOT)).isEqualTo(Material.RED_BED);
        assertThat(HubLook.forCommand("sethome", Material.GOLD_INGOT)).isEqualTo(Material.RED_BED);
        assertThat(HubLook.forCommand("/PAY", Material.PAPER)).isEqualTo(Material.GOLD_NUGGET);
        assertThat(HubLook.forCommand("xyzzy", Material.LANTERN)).isEqualTo(Material.LANTERN);
    }
}
