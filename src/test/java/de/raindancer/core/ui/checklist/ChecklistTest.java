package de.raindancer.core.ui.checklist;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChecklistTest {

    private static Checklist preflight(boolean world, boolean practice) {
        return Checklist.titled("Before the start")
                .check(Checklist.Check.of("world", "The arena world exists", world)
                        .because(world ? "'arena' is loaded." : "'arena' is not loaded.")
                        .fixedBy("Create it", player -> { }, "game.admin"))
                .check(Checklist.Check.warning("practice", "A real run", !practice)
                        .because("Practice kit is on."));
    }

    @Test
    @DisplayName("ready only when nothing that blocks is red; warnings never stop it")
    void readiness() {
        assertThat(preflight(true, false).ready()).isTrue();
        assertThat(preflight(true, true).ready()).as("a warning").isTrue();
        assertThat(preflight(false, false).ready()).isFalse();
        assertThat(preflight(false, true).problems()).extracting(Checklist.Check::id)
                .containsExactly("world", "practice");
    }

    @Test
    @DisplayName("a summary a person can read")
    void summary() {
        assertThat(preflight(true, false).summary()).isEqualTo("All 2 checks are fine.");
        assertThat(preflight(true, true).summary()).isEqualTo("Ready, with 1 warning.");
        assertThat(preflight(false, false).summary()).isEqualTo("1 of 2 fine; 1 thing stops the start.");
    }

    @Test
    @DisplayName("a game mode's own checks are appended under their own heading; an id replaces")
    void modesAppend() {
        Checklist mode = Checklist.titled("Manhunt")
                .check(Checklist.Check.of("hunters", "At least one hunter", false).in("Manhunt"))
                .check(Checklist.Check.of("world", "Replaced", true));

        Checklist all = preflight(false, false).and(mode);

        assertThat(all.checks()).extracting(Checklist.Check::id).containsExactly("world", "practice", "hunters");
        assertThat(all.byId("world")).hasValueSatisfying(check -> assertThat(check.label()).isEqualTo("Replaced"));
        assertThat(all.byGroup()).containsOnlyKeys("", "Manhunt");
        assertThat(all.blockers()).extracting(Checklist.Check::id).containsExactly("hunters");
    }

    @Test
    @DisplayName("a fix is offered only on a red line, and only to somebody allowed to use it")
    void fixes() {
        AtomicInteger ran = new AtomicInteger();
        Checklist.Check red = Checklist.Check.of("x", "X", false).fixedBy("Do it", player -> ran.incrementAndGet(), "perm");
        Player admin = mock(Player.class);
        when(admin.hasPermission("perm")).thenReturn(true);
        Player guest = mock(Player.class);

        assertThat(red.fixIfAny()).hasValueSatisfying(fix -> {
            assertThat(fix.allowedFor(admin)).isTrue();
            assertThat(fix.allowedFor(guest)).isFalse();
            fix.action().accept(admin);
        });
        assertThat(ran).hasValue(1);
        assertThat(Checklist.Check.of("y", "Y", true).fixedBy("No", player -> { }).fixIfAny()).isEmpty();
    }

    @Test
    @DisplayName("in chat: a title with the summary, then one line per check, headings between groups")
    void chatLines() {
        Checklist list = preflight(false, true)
                .and(Checklist.titled("m").check(Checklist.Check.of("hunters", "Hunters", true).in("Manhunt")));

        List<Component> lines = ChecklistChat.lines(list, null, null);

        List<String> plain = lines.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
        assertThat(plain).containsExactly(
                "Before the start: 1 of 3 fine; 1 thing stops the start.",
                " ✘ The arena world exists: 'arena' is not loaded.",
                " ⚠ A real run: Practice kit is on.",
                "Manhunt",
                " ✔ Hunters");
    }

    @Test
    @DisplayName("a label or reason holding a tag shows the tag, never acts on it")
    void textIsText() {
        Checklist list = Checklist.titled("T").check(Checklist.Check.of("w", "World <click:run_command:'/op x'>x", false));

        assertThat(PlainTextComponentSerializer.plainText().serialize(ChecklistChat.lines(list, null, null).get(1)))
                .contains("<click:run_command:'/op x'>");
    }
}
