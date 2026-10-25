package de.raindancer.core.ui.help;

import de.raindancer.core.world.npc.Skins;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HelpSettingsTest {

    @Test
    @DisplayName("a guide placed as a person is the Wise Old Man unless the server chose another skin")
    void wiseOldMan() {
        assertThat(Skins.mineSkinId(HelpSettings.DEFAULTS.guideSkins())).contains("09625d300be0469db9d173dcb6a32bef");
    }
}
