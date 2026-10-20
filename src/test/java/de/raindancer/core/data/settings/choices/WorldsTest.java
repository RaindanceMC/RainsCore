package de.raindancer.core.data.settings.choices;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("The worlds a world-list setting may name")
class WorldsTest {

    @Test
    @DisplayName("without a server — a schema built in a test — there are none, and nothing throws")
    void noServer() {
        assertThat(new Worlds().get()).isEmpty();
    }
}
