package de.raindancer.core.ui.messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FromElsewhereTest {

    @Test
    @DisplayName("a line from another server keeps its words and colours but can make the reader run nothing")
    void noClicks() {
        Component forged = Component.text("You paid ").append(Component.text("Bea")
                .clickEvent(ClickEvent.runCommand("/op Mallory")).insertion("/op Mallory")
                .append(Component.text("100").clickEvent(ClickEvent.suggestCommand("/pay Mallory 100000"))));
        String json = GsonComponentSerializer.gson().serialize(forged);

        Component shown = FromElsewhere.line(json);

        String back = GsonComponentSerializer.gson().serialize(shown);
        assertThat(back).doesNotContain("clickEvent").doesNotContain("click_event").doesNotContain("insertion")
                .contains("You paid").contains("Bea").contains("100");
    }

    @Test
    @DisplayName("something that is not a component at all is shown as plain text, not thrown")
    void garbage() {
        assertThat(GsonComponentSerializer.gson().serialize(FromElsewhere.line("{not json")))
                .contains("not json");
    }

    @Test
    @DisplayName("nor through a translation's arguments")
    void noClicksInArguments() {
        Component forged = Component.translatable("chat.type.text", Component.text("Bea"),
                Component.text("click me").clickEvent(ClickEvent.runCommand("/op Mallory")));
        String back = GsonComponentSerializer.gson().serialize(
                FromElsewhere.line(GsonComponentSerializer.gson().serialize(forged)));
        assertThat(back).doesNotContain("/op Mallory").contains("click me");
    }
}
