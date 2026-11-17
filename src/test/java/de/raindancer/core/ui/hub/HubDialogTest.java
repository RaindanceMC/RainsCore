package de.raindancer.core.ui.hub;

import de.raindancer.core.platform.command.CommandNote;
import de.raindancer.core.ui.dialog.DialogAnswers;
import de.raindancer.core.ui.dialog.DialogPage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HubDialogTest {

    private final List<DialogPage> shown = new ArrayList<>();
    private final List<String> ran = new ArrayList<>();
    private final List<String> typed = new ArrayList<>();
    private final Player ada = mock(Player.class);

    private final HubEntry pets = new HubEntry("pets", "Pets", "0.19.0", "Pets that follow you.", Material.BONE,
            "#F2843A", null, List.of("pets"));
    private final HubEntry economy = new HubEntry("economy", "Economy", "0.75.0", "Money.", Material.GOLD_INGOT,
            "#F6B83D", null, List.of("bank", "pay"));

    private static CommandNote note(String plugin, String command, String... options) {
        return new CommandNote(plugin, command, "Does " + command + ".", List.of(options), null);
    }

    private final class Fake implements HubDialog.Source {

        Set<String> commands = Set.of("commands", "changelog");
        boolean travel = true;

        @Override
        public List<HubEntry> entries(Player viewer) {
            return List.of(economy, pets);
        }

        @Override
        public List<CommandNote> notes(HubEntry entry, Player viewer) {
            return entry == pets ? List.of(note("Pets", "pets"))
                    : List.of(note("Economy", "bank"), note("Economy", "pay", "player", "amount"));
        }

        @Override
        public boolean exists(String command) {
            return commands.contains(command);
        }

        @Override
        public boolean travel() {
            return travel;
        }

        @Override
        public String serverName() {
            return "Hyades";
        }

        @Override
        public String name(Player viewer) {
            return "<gradient:#FF0000:#00FF00>Ada-Painted</gradient>";
        }

        @Override
        public List<String> facts(Player viewer) {
            return List.of("<gray>Online now: <white>3");
        }

        @Override
        public void run(Player viewer, String command) {
            ran.add(command);
        }

        @Override
        public void suggest(Player viewer, String command) {
            typed.add(command);
        }
    }

    private final Fake source = new Fake();

    @BeforeEach
    void standIn() {
        when(ada.isOnline()).thenReturn(true);
        when(ada.getName()).thenReturn("Ada");
        DialogPage.runtime(new DialogPage.Runtime() {
            @Override
            public void show(Player player, DialogPage page) {
                shown.add(page);
            }

            @Override
            public void onPlayerThread(Player player, Runnable task) {
                task.run();
            }
        });
    }

    @AfterEach
    void game() {
        DialogPage.runtime(null);
    }

    private static List<String> labels(DialogPage page) {
        return page.pressable().stream().map(button -> PlainTextComponentSerializer.plainText().serialize(button.label()))
                .toList();
    }

    @Test
    @DisplayName("the hub window: the server, the player's own doors, then every feature")
    void hubPage() {
        DialogPage page = HubDialog.page(ada, source);
        assertThat(PlainTextComponentSerializer.plainText().serialize(page.title())).contains("Hyades");
        assertThat(page.plainText()).contains("Ada").contains("Online now: 3");
        assertThat(labels(page)).containsSubsequence("Travel", "Your preferences", "Your profile", "What's new",
                "All commands", "Economy", "Pets");
        assertThat(labels(page)).doesNotContain("Server settings");
    }

    @Test
    @DisplayName("staff see the server settings; without travel doors there is no Travel")
    void staffAndTravel() {
        source.commands = Set.of("settings");
        source.travel = false;
        when(ada.hasPermission("rainscore.settings")).thenReturn(true);
        List<String> labels = labels(HubDialog.page(ada, source));
        assertThat(labels).contains("Server settings").doesNotContain("Travel", "What's new", "All commands");
    }

    @Test
    @DisplayName("a feature with a command named like it opens straight away; another opens its own window of commands")
    void features() {
        DialogPage hub = HubDialog.page(ada, source);
        hub.button("Pets").press(ada, DialogAnswers.none());
        assertThat(ran).containsExactly("pets");

        hub.button("Economy").press(ada, DialogAnswers.none());
        DialogPage economyPage = shown.getLast();
        assertThat(PlainTextComponentSerializer.plainText().serialize(economyPage.title())).contains("Economy");
        assertThat(labels(economyPage)).contains("/bank", "/pay");
        economyPage.button("/bank").press(ada, DialogAnswers.none());
        assertThat(ran).containsExactly("pets", "bank");
        economyPage.button("/pay").press(ada, DialogAnswers.none());
        assertThat(typed).containsExactly("/pay");
    }

    @Test
    @DisplayName("every button the viewer may press says what a click does")
    void clickHints() {
        DialogPage page = HubDialog.page(ada, source);
        assertThat(page.pressable().stream().filter(DialogPage.Button::allowed)
                .map(button -> PlainTextComponentSerializer.plainText().serialize(button.tooltip())).toList())
                .isNotEmpty().allSatisfy(tooltip -> assertThat(tooltip).contains("Click to"));
    }

    @Test
    @DisplayName("the welcome shows the name in the player's own colours")
    void paintedName() {
        DialogPage page = HubDialog.page(ada, source);
        assertThat(page.plainText()).contains("Welcome, Ada-Painted");
    }
}
