package de.raindancer.core.ui.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RankTitlesTest {

    private static final UUID TOM = UUID.randomUUID();

    @AfterEach
    void clear() {
        RankTitles.clear();
    }

    @Test
    @DisplayName("without a ranks plugin nobody has a title")
    void nobodyProvides() {
        assertThat(RankTitles.of(TOM)).isEmpty();
        assertThat(RankTitles.of(null)).isEmpty();
    }

    @Test
    @DisplayName("whoever runs ranks answers; blank answers and a failing source are no title; withdrawing stops it")
    void provided() {
        RankTitles.Source source = player -> player.equals(TOM) ? Optional.of("Owner") : Optional.of(" ");
        RankTitles.provide(source);

        assertThat(RankTitles.of(TOM)).contains("Owner");
        assertThat(RankTitles.of(UUID.randomUUID())).isEmpty();

        RankTitles.provide(player -> {
            throw new IllegalStateException("boom");
        });
        assertThat(RankTitles.of(TOM)).isEmpty();

        RankTitles.provide(source);
        RankTitles.withdraw(source);
        assertThat(RankTitles.of(TOM)).isEmpty();
    }
}
