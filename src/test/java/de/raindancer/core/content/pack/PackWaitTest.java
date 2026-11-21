package de.raindancer.core.content.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("waiting for a player's pack before playing what is in it")
class PackWaitTest {

    @Test
    @DisplayName("on: go; still coming: wait; refused, failed or too long: give up")
    void steps() {
        assertThat(PackWait.step(PackStatus.LOADED, 1)).isEqualTo(PackWait.Step.GO);
        assertThat(PackWait.step(PackStatus.ALREADY_HAD_IT, 0)).isEqualTo(PackWait.Step.GO);
        assertThat(PackWait.step(PackStatus.SENT, 5)).isEqualTo(PackWait.Step.WAIT);
        assertThat(PackWait.step(PackStatus.NOT_SENT, 5)).isEqualTo(PackWait.Step.WAIT);
        assertThat(PackWait.step(PackStatus.SENT, PackWait.PATIENCE_SECONDS)).isEqualTo(PackWait.Step.GIVE_UP);
        assertThat(PackWait.step(PackStatus.DECLINED, 0)).isEqualTo(PackWait.Step.GIVE_UP);
        assertThat(PackWait.step(PackStatus.FAILED, 0)).isEqualTo(PackWait.Step.GIVE_UP);
    }
}
