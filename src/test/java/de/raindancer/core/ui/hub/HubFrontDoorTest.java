package de.raindancer.core.ui.hub;

import de.raindancer.core.platform.command.CommandNote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HubFrontDoorTest {

    private static CommandNote note(String command, String... options) {
        return new CommandNote("Guilds", command, "Does a thing.", List.of(options), null);
    }

    @Test
    @DisplayName("a feature whose own command runs outright is one click away")
    void theFeaturesOwnCommandIsTheFrontDoor() {
        HubEntry guilds = HubEntry.of("guilds", "Guilds", "1.0", "Guilds.", List.of("guild"));
        assertThat(HubDialog.frontDoor(guilds, List.of(note("guild"), note("guild invite", "player"))))
                .map(CommandNote::command).contains("guild");
        HubEntry pets = HubEntry.of("pets", "Pets", "1.0", "Pets.", List.of("pets"));
        assertThat(HubDialog.frontDoor(pets, List.of(note("pets")))).map(CommandNote::command).contains("pets");
        HubEntry claims = HubEntry.of("claims", "Claims", "1.0", "Claims.", List.of("claim"));
        assertThat(HubDialog.frontDoor(claims, List.of(note("claim")))).map(CommandNote::command).contains("claim");
    }

    @Test
    @DisplayName("a feature with doors of other names, or one that needs something typed, opens its page")
    void otherwiseThePageOfDoors() {
        HubEntry economy = HubEntry.of("economy", "Economy", "1.0", "Money.", List.of("bank", "shop"));
        assertThat(HubDialog.frontDoor(economy, List.of(note("bank"), note("shop")))).isEmpty();
        HubEntry tpa = HubEntry.of("tpa", "Teleports", "1.0", "Asks.", List.of("tpa"));
        assertThat(HubDialog.frontDoor(tpa, List.of(note("tpa", "player")))).isEmpty();
        assertThat(HubDialog.frontDoor(tpa, List.of())).isEmpty();
    }
}
