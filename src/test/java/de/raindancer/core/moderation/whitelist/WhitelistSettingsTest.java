package de.raindancer.core.moderation.whitelist;

import de.raindancer.core.data.settings.SettingsSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class WhitelistSettingsTest {

    @Test
    @DisplayName("the record is a valid settings schema, and servers share the whitelist by default")
    void validSchema() {
        assertThatCode(() -> SettingsSchema.of(WhitelistSettings.class, WhitelistSettings.DEFAULTS))
                .doesNotThrowAnyException();
        assertThat(WhitelistSettings.DEFAULTS.shared()).isTrue();
    }
}
