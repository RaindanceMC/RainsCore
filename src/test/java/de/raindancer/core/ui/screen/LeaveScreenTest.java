package de.raindancer.core.ui.screen;

import de.raindancer.core.data.settings.SettingsSchema;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LeaveScreenTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("the body sits in the frame: the name on top, a divider, the body, the footer last")
    void framed() {
        Component screen = LeaveScreen.frame("Lilly's SMP", Component.text("You are banned"), "discord.gg/lillyyxoxo");

        assertThat(plain(screen)).isEqualTo("""
                ✦ LILLY'S SMP ✦
                ━━━━━━━━━━━━━━

                You are banned

                discord.gg/lillyyxoxo""");
    }

    @Test
    @DisplayName("without a footer the screen ends with the body")
    void noFooter() {
        assertThat(plain(LeaveScreen.frame("Lilly's SMP", Component.text("Bye"), " ")))
                .isEqualTo("✦ LILLY'S SMP ✦\n━━━━━━━━━━━━━━\n\nBye");
    }

    @Test
    @DisplayName("a server without a name gets the body alone, not an empty header")
    void noName() {
        assertThat(plain(LeaveScreen.frame("", Component.text("Bye"), "x.net"))).isEqualTo("Bye\n\nx.net");
    }

    @Test
    @DisplayName("the name is shown as typed, never read as markup")
    void nameIsPlain() {
        assertThat(plain(LeaveScreen.frame("<red>Evil", Component.text("Bye"), ""))).startsWith("✦ <RED>EVIL ✦");
    }

    @Test
    @DisplayName("the header is bold and runs from one end of the brand gradient to the other")
    void headerInTheBrandGradient() {
        Component header = LeaveScreen.header("Lilly's SMP", "#ff5fa2", "#a24bff");
        List<TextComponent> letters = new ArrayList<>();
        collect(header, letters);
        List<TextComponent> visible = letters.stream().filter(l -> !l.content().isBlank()).toList();

        assertThat(header.decoration(TextDecoration.BOLD)).isEqualTo(TextDecoration.State.TRUE);
        assertThat(visible.getFirst().color()).isEqualTo(TextColor.fromHexString("#ff5fa2"));
        assertThat(visible.getLast().color()).isEqualTo(TextColor.fromHexString("#a24bff"));
    }

    @Test
    @DisplayName("framed uses what the server configured, asked anew each time")
    void configured() {
        String[] title = {"Lilly's SMP"};
        try {
            LeaveScreen.configure(() -> title[0], () -> "lillyyxoxo.net");
            assertThat(plain(LeaveScreen.framed(Component.text("Bye")))).startsWith("✦ LILLY'S SMP ✦").endsWith("lillyyxoxo.net");
            title[0] = "Dawn";
            assertThat(plain(LeaveScreen.framed(Component.text("Bye")))).startsWith("✦ DAWN ✦");
        } finally {
            LeaveScreen.configure(() -> "", () -> "");
        }
    }

    @Test
    @DisplayName("never configured (a test, a plugin without Core running), the body is left as it is")
    void unconfigured() {
        assertThat(plain(LeaveScreen.framed(Component.text("Bye")))).isEqualTo("Bye");
    }

    @Test
    @DisplayName("the settings are a valid schema, and empty by default: the server's name, no footer")
    void settings() {
        assertThatCode(() -> SettingsSchema.of(LeaveScreenSettings.class, LeaveScreenSettings.DEFAULTS))
                .doesNotThrowAnyException();
        assertThat(LeaveScreenSettings.DEFAULTS.title()).isEmpty();
        assertThat(LeaveScreenSettings.DEFAULTS.footer()).isEmpty();
    }

    private static void collect(Component component, List<TextComponent> into) {
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            into.add(text.color() == null ? text.color(component.color()) : text);
        }
        for (Component child : component.children()) {
            collect(child.colorIfAbsent(component.color()), into);
        }
    }
}
