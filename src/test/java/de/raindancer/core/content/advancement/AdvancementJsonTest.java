package de.raindancer.core.content.advancement;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdvancementJsonTest {

    private static AdvancementDef.Builder base() {
        return AdvancementDef.builder("enchants", "first").title("<gold>First").description("Do it")
                .icon(Material.DIAMOND);
    }

    private static JsonObject json(AdvancementDef def) {
        return JsonParser.parseString(AdvancementJson.of(def)).getAsJsonObject();
    }

    @Test
    @DisplayName("a plain advancement is one impossible criterion, so only the plugin can grant it")
    void singleCriterion() {
        JsonObject json = json(base().build());

        assertThat(json.getAsJsonObject("criteria").keySet()).containsExactly("step_1");
        assertThat(json.getAsJsonObject("criteria").getAsJsonObject("step_1").get("trigger").getAsString())
                .isEqualTo("minecraft:impossible");
        assertThat(json.has("parent")).isFalse();
    }

    @Test
    @DisplayName("display carries icon, frame, and the toast/chat/hidden flags")
    void display() {
        JsonObject display = json(base().frame(AdvancementFrame.CHALLENGE).hidden(true).toast(false)
                .announce(false).build()).getAsJsonObject("display");

        assertThat(display.getAsJsonObject("icon").get("id").getAsString()).isEqualTo("minecraft:diamond");
        assertThat(display.get("frame").getAsString()).isEqualTo("challenge");
        assertThat(display.get("hidden").getAsBoolean()).isTrue();
        assertThat(display.get("show_toast").getAsBoolean()).isFalse();
        assertThat(display.get("announce_to_chat").getAsBoolean()).isFalse();
    }

    @Test
    @DisplayName("title and description are text components, MiniMessage already resolved")
    void components() {
        JsonObject display = json(base().build()).getAsJsonObject("display");

        assertThat(display.get("title").toString()).contains("First").contains("gold");
        assertThat(display.get("description").toString()).contains("Do it");
    }

    @Test
    @DisplayName("a goal with n steps has n criteria that must all be met")
    void steps() {
        JsonObject json = json(base().steps(3).build());

        assertThat(json.getAsJsonObject("criteria").keySet()).containsExactly("step_1", "step_2", "step_3");
        assertThat(json.getAsJsonArray("requirements")).hasSize(3);
    }

    @Test
    @DisplayName("a bare parent id means the same plugin's; a namespaced one is taken as it is")
    void parent() {
        assertThat(json(base().parent("root").build()).get("parent").getAsString()).isEqualTo("enchants:root");
        assertThat(json(base().parent("minecraft:story/root").build()).get("parent").getAsString())
                .isEqualTo("minecraft:story/root");
    }

    @Test
    @DisplayName("only a root has a background, and it is written for it")
    void background() {
        assertThat(json(base().background("minecraft:gui/advancements/backgrounds/stone").build())
                .getAsJsonObject("display").get("background").getAsString())
                .isEqualTo("minecraft:gui/advancements/backgrounds/stone");
        assertThat(json(base().parent("root").background("x").build()).getAsJsonObject("display").has("background"))
                .isFalse();
    }

    @Test
    @DisplayName("ids that Minecraft would refuse are refused here, with the reason")
    void badIds() {
        assertThatThrownBy(() -> AdvancementDef.builder("Enchants", "Has Space").title("x").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdvancementDef.builder("enchants", "ok").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
    }
}
