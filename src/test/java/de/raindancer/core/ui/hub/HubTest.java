package de.raindancer.core.ui.hub;

import de.raindancer.core.platform.command.CommandDirectory;
import de.raindancer.core.platform.command.CommandNote;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HubTest {

    private static HubEntry entry(String id, String name, String... commands) {
        return new HubEntry(id, name, "1.0.0", "Does " + name + ".", Material.PAPER, "#FFAA00", null,
                List.of(commands));
    }

    @Test
    @DisplayName("re-declaring an id replaces it, and the list is alphabetical")
    void declareReplacesAndSorts() {
        Hub hub = new Hub()
                .declare(entry("warp", "Warps"))
                .declare(entry("homes", "Homes"))
                .declare(entry("warp", "Warps again"));
        assertThat(hub.all()).extracting(HubEntry::name).containsExactly("Homes", "Warps again");
    }

    @Test
    @DisplayName("forgetting an id removes it, and an unknown id is not an error")
    void forget() {
        Hub hub = new Hub().declare(entry("warp", "Warps"));
        assertThat(hub.forget("warp")).isTrue();
        assertThat(hub.forget("warp")).isFalse();
        assertThat(hub.all()).isEmpty();
    }

    @Test
    @DisplayName("an entry needing a permission is hidden from whoever lacks it")
    void permissionHides() {
        Hub hub = new Hub().declare(new HubEntry("mod", "Moderation", "1", "", Material.IRON_SWORD,
                "#FF0000", "rains.staff", List.of()));
        assertThat(hub.visibleTo(node -> false, new CommandDirectory())).isEmpty();
        assertThat(hub.visibleTo(node -> true, new CommandDirectory())).hasSize(1);
    }

    @Test
    @DisplayName("a module whose every reported command is staff-only is absent for a player")
    void allCommandsHiddenHidesTheModule() {
        CommandDirectory directory = new CommandDirectory()
                .declare(CommandNote.of("RainsModeration", "ban", "Ban.").needing("mod.ban"))
                .declare(CommandNote.of("RainsHomes", "home", "Go home."));
        Hub hub = new Hub()
                .declare(entry("moderation", "Moderation", "ban"))
                .declare(entry("homes", "Homes", "home"));
        assertThat(hub.visibleTo(node -> false, directory)).extracting(HubEntry::id).containsExactly("homes");
        assertThat(hub.visibleTo(node -> true, directory)).hasSize(2);
    }

    @Test
    @DisplayName("commands nobody reported cannot be judged, so the module stays visible")
    void unreportedStaysVisible() {
        Hub hub = new Hub().declare(entry("warp", "Warps", "warp"));
        assertThat(hub.visibleTo(node -> false, new CommandDirectory())).hasSize(1);
    }

    @Test
    @DisplayName("a module with no commands at all (a pure feature) is shown to everybody")
    void noCommandsIsVisible() {
        Hub hub = new Hub().declare(entry("veintoggle", "Veins"));
        assertThat(hub.visibleTo(node -> false, new CommandDirectory())).hasSize(1);
    }

    @Test
    @DisplayName("find matches id or name, case-insensitively, exact before partial")
    void find() {
        List<HubEntry> entries = List.of(entry("homes", "Homes"), entry("tpa", "Teleport Requests"));
        assertThat(Hub.find(entries, "HOMES")).map(HubEntry::id).contains("homes");
        assertThat(Hub.find(entries, "tele")).map(HubEntry::id).contains("tpa");
        assertThat(Hub.find(entries, "zzz")).isEmpty();
    }

    @Test
    @DisplayName("an entry's commands are looked up in the directory by their first word")
    void notesFor() {
        CommandDirectory directory = new CommandDirectory()
                .declare(CommandNote.of("P", "home", "Go home."))
                .declare(CommandNote.of("P", "home set", "Set one."))
                .declare(CommandNote.of("P", "warp", "Warp."));
        List<CommandNote> notes = Hub.notesFor(entry("homes", "Homes", "home"), directory, Set.of()::contains);
        assertThat(notes).extracting(CommandNote::command).containsExactly("home", "home set");
    }

    @Test
    @DisplayName("an entry refuses a blank name or a malformed accent")
    void validation() {
        assertThatThrownBy(() -> entry("x", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HubEntry("x", "X", "1", "", Material.PAPER, "orange", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
