package de.raindancer.core.social.support;

import de.raindancer.core.data.store.YamlStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SupportersTest {

    @TempDir
    Path folder;

    private final UUID player = UUID.randomUUID();
    private final Instant first = Instant.parse("2026-10-26T10:00:00Z");

    private Supporters fresh() {
        Supporters supporters = new Supporters(new YamlStore(folder.resolve("supporters.yml"), null,
                de.raindancer.core.data.store.Documents.Scope.NETWORK));
        supporters.load();
        return supporters;
    }

    @Test
    @DisplayName("nobody is a supporter until credited")
    void nobodyAtFirst() {
        assertThat(fresh().isSupporter(player)).isFalse();
        assertThat(fresh().of(player)).isEmpty();
    }

    @Test
    @DisplayName("credited coffees add up; the first time stays the start, and they are not yet thanked")
    void creditsAddUp() {
        Supporters supporters = fresh();

        supporters.credit(player, 1, first);
        supporters.credit(player, 3, first.plusSeconds(3600));

        Supporters.Supporter them = supporters.of(player).orElseThrow();
        assertThat(supporters.isSupporter(player)).isTrue();
        assertThat(them.coffees()).isEqualTo(4);
        assertThat(them.since()).isEqualTo(first);
        assertThat(them.thanked()).isFalse();
    }

    @Test
    @DisplayName("a thank-you is remembered, and a new coffee asks for the next one")
    void thanked() {
        Supporters supporters = fresh();
        supporters.credit(player, 1, first);

        supporters.thanked(player);
        assertThat(supporters.of(player).orElseThrow().thanked()).isTrue();

        supporters.credit(player, 1, first.plusSeconds(60));
        assertThat(supporters.of(player).orElseThrow().thanked()).isFalse();
    }

    @Test
    @DisplayName("a coffee count of zero or less still makes a supporter of one coffee — a webhook can say 0")
    void atLeastOne() {
        Supporters supporters = fresh();

        supporters.credit(player, 0, first);

        assertThat(supporters.of(player).orElseThrow().coffees()).isEqualTo(1);
    }

    @Test
    @DisplayName("taken away, they are no supporter any more")
    void revoke() {
        Supporters supporters = fresh();
        supporters.credit(player, 2, first);

        assertThat(supporters.revoke(player)).isTrue();
        assertThat(supporters.isSupporter(player)).isFalse();
        assertThat(supporters.revoke(player)).isFalse();
    }

    @Test
    @DisplayName("everything survives being written and read back, a revoke included")
    void persists() {
        Supporters supporters = fresh();
        UUID gone = UUID.randomUUID();
        supporters.credit(player, 2, first);
        supporters.credit(gone, 1, first);
        supporters.thanked(player);
        supporters.revoke(gone);
        assertThat(supporters.save()).isTrue();

        Supporters reread = fresh();

        assertThat(reread.of(player).orElseThrow()).isEqualTo(new Supporters.Supporter(player, first, 2, true));
        assertThat(reread.isSupporter(gone)).isFalse();
        assertThat(reread.all()).hasSize(1);
    }
}
