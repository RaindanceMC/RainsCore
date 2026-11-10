package de.raindancer.core.platform.proxy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandoffTest {

    @Test
    void whatOneServerLeavesTheNextReads() {
        assertThat(Handoff.decode(Handoff.encode("tour"))).contains("tour");
    }

    @Test
    void nothingLeftIsNothing() {
        assertThat(Handoff.decode(null)).isEmpty();
        assertThat(Handoff.decode(new byte[0])).isEmpty();
        assertThat(Handoff.decode("  ".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void aClientHoldsOnlySoMuch() {
        assertThat(Handoff.encode("x".repeat(Handoff.MAX_BYTES))).hasSize(Handoff.MAX_BYTES);
        assertThatThrownBy(() -> Handoff.encode("x".repeat(Handoff.MAX_BYTES + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Handoff.encode(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
