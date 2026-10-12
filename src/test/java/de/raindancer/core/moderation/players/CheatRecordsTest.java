package de.raindancer.core.moderation.players;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CheatRecordsTest {

    private static final UUID CHEATER = UUID.randomUUID();
    private static final CheatRecords.Summary FLYING = new CheatRecords.Summary(12.5, "Fly", 3, "Speed", 1000L, 2);

    @AfterEach
    void clear() {
        CheatRecords.clear();
    }

    @Test
    @DisplayName("without an anti-cheat there is no record, and nobody pretends there is")
    void nobody() {
        assertThat(CheatRecords.available()).isFalse();
        assertThat(CheatRecords.of(CHEATER)).isEmpty();
    }

    @Test
    @DisplayName("whatever the anti-cheat knows of a player is handed over as it is")
    void provided() {
        CheatRecords.provide(who -> who.equals(CHEATER) ? Optional.of(FLYING) : Optional.empty());
        assertThat(CheatRecords.available()).isTrue();
        assertThat(CheatRecords.of(CHEATER)).contains(FLYING);
        assertThat(CheatRecords.of(UUID.randomUUID())).isEmpty();
        assertThat(CheatRecords.of(null)).isEmpty();
    }

    @Test
    @DisplayName("an anti-cheat that throws counts as knowing nothing")
    void broken() {
        CheatRecords.provide(who -> {
            throw new IllegalStateException("broken");
        });
        assertThat(CheatRecords.of(CHEATER)).isEmpty();
    }

    @Test
    @DisplayName("an anti-cheat going away takes its records with it")
    void forgetFrom() {
        CheatRecords.provide(who -> Optional.of(FLYING));
        assertThat(CheatRecords.forgetFrom(CheatRecordsTest.class.getClassLoader())).isEqualTo(1);
        assertThat(CheatRecords.available()).isFalse();
    }
}
