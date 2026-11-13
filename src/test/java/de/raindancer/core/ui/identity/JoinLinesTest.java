package de.raindancer.core.ui.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Somebody's own join and leave line, asked for by whoever writes them. */
class JoinLinesTest {

    private final UUID player = UUID.randomUUID();

    @AfterEach
    void clear() {
        JoinLines.provider(null);
    }

    @Test
    @DisplayName("with nobody providing lines, the server's own template is used")
    void fallsBack() {
        assertThat(JoinLines.template(player, JoinLines.Kind.JOIN, "<player> joined")).isEqualTo("<player> joined");
        assertThat(JoinLines.custom(player, JoinLines.Kind.QUIT)).isEmpty();
    }

    @Test
    @DisplayName("a provider's line replaces the template, for the kind it was given for")
    void providerWins() {
        JoinLines.provider((who, kind) -> who.equals(player)
                ? Optional.of(kind == JoinLines.Kind.JOIN ? "<player> sparkles in" : "<player> fades away")
                : Optional.empty());
        assertThat(JoinLines.template(player, JoinLines.Kind.JOIN, "x")).isEqualTo("<player> sparkles in");
        assertThat(JoinLines.template(player, JoinLines.Kind.QUIT, "x")).isEqualTo("<player> fades away");
        assertThat(JoinLines.template(UUID.randomUUID(), JoinLines.Kind.JOIN, "x")).isEqualTo("x");
    }

    @Test
    @DisplayName("a provider that fails or answers null never costs anybody their join line")
    void brokenProvider() {
        JoinLines.provider((who, kind) -> {
            throw new IllegalStateException("boom");
        });
        assertThat(JoinLines.template(player, JoinLines.Kind.JOIN, "x")).isEqualTo("x");
        JoinLines.provider((who, kind) -> null);
        assertThat(JoinLines.template(player, JoinLines.Kind.JOIN, "x")).isEqualTo("x");
    }

    @Test
    @DisplayName("a blank line is no line")
    void blank() {
        JoinLines.provider((who, kind) -> Optional.of("  "));
        assertThat(JoinLines.template(player, JoinLines.Kind.JOIN, "x")).isEqualTo("x");
    }
}
