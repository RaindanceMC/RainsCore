package de.raindancer.core.world.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("sleeping with pet monsters about")
class SleepingTest {

    @Test
    @DisplayName("only companions near the bed: they are nobody's danger, the bed is let in")
    void onlyCompanions() {
        assertThat(Sleeping.letIn(List.of(true))).isTrue();
        assertThat(Sleeping.letIn(List.of(true, true, true))).isTrue();
    }

    @Test
    @DisplayName("a real monster among them still keeps the player up, and with nothing near it is not ours to say")
    void realMonsters() {
        assertThat(Sleeping.letIn(List.of(true, false))).isFalse();
        assertThat(Sleeping.letIn(List.of(false))).isFalse();
        assertThat(Sleeping.letIn(List.of())).isFalse();
    }
}
