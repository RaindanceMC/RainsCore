package de.raindancer.core.social.support;

import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrimeMembersTest {

    @TempDir
    Path folder;

    private final UUID ada = UUID.randomUUID();
    private final Instant nov13 = Instant.parse("2026-11-13T12:00:00Z");

    private PrimeMembers fresh() {
        PrimeMembers prime = new PrimeMembers(new YamlStore(folder.resolve("prime.yml"), null, Documents.Scope.NETWORK));
        prime.load();
        return prime;
    }

    @Test
    @DisplayName("nobody is Prime until granted")
    void nobody() {
        PrimeMembers prime = fresh();
        assertThat(prime.isPrime(ada, nov13)).isFalse();
        assertThat(prime.of(ada)).isEmpty();
        assertThat(prime.months(ada)).isEmpty();
    }

    @Test
    @DisplayName("a grant runs for its days and covers every month it touches")
    void grant() {
        PrimeMembers prime = fresh();
        prime.grant(ada, Duration.ofDays(31), nov13);

        assertThat(prime.isPrime(ada, nov13)).isTrue();
        assertThat(prime.isPrime(ada, nov13.plus(Duration.ofDays(30)))).isTrue();
        assertThat(prime.isPrime(ada, nov13.plus(Duration.ofDays(32)))).isFalse();
        assertThat(prime.months(ada)).containsExactly(YearMonth.of(2026, 11), YearMonth.of(2026, 12));
    }

    @Test
    @DisplayName("a renewal before the end extends from the end, not from now; the start stays")
    void renewEarly() {
        PrimeMembers prime = fresh();
        prime.grant(ada, Duration.ofDays(31), nov13);
        prime.grant(ada, Duration.ofDays(31), nov13.plus(Duration.ofDays(28)));

        PrimeMembers.Member member = prime.of(ada).orElseThrow();
        assertThat(member.until()).isEqualTo(nov13.plus(Duration.ofDays(62)));
        assertThat(member.since()).isEqualTo(nov13);
        assertThat(prime.months(ada)).containsExactly(YearMonth.of(2026, 11), YearMonth.of(2026, 12),
                YearMonth.of(2027, 1));
    }

    @Test
    @DisplayName("coming back after a gap starts from now and keeps the old months, not the ones missed")
    void comeBack() {
        PrimeMembers prime = fresh();
        prime.grant(ada, Duration.ofDays(10), nov13);
        Instant march = Instant.parse("2027-03-02T00:00:00Z");
        prime.grant(ada, Duration.ofDays(10), march);

        assertThat(prime.months(ada)).containsExactly(YearMonth.of(2026, 11), YearMonth.of(2027, 3));
        assertThat(prime.of(ada).orElseThrow().since()).isEqualTo(march);
    }

    @Test
    @DisplayName("a revoke ends it now and keeps the months already had")
    void revoke() {
        PrimeMembers prime = fresh();
        prime.grant(ada, Duration.ofDays(31), nov13);
        assertThat(prime.revoke(ada, nov13.plusSeconds(60))).isTrue();

        assertThat(prime.isPrime(ada, nov13.plusSeconds(61))).isFalse();
        assertThat(prime.months(ada)).contains(YearMonth.of(2026, 11));
        assertThat(prime.revoke(ada, nov13.plusSeconds(120))).isFalse();
    }

    @Test
    @DisplayName("current lists only who is Prime right now")
    void current() {
        PrimeMembers prime = fresh();
        UUID bo = UUID.randomUUID();
        prime.grant(ada, Duration.ofDays(31), nov13);
        prime.grant(bo, Duration.ofDays(1), nov13);

        assertThat(prime.current(nov13.plus(Duration.ofDays(2)))).extracting(PrimeMembers.Member::player)
                .containsExactly(ada);
    }

    @Test
    @DisplayName("saved and read back by another server")
    void roundTrip() {
        PrimeMembers prime = fresh();
        prime.grant(ada, Duration.ofDays(31), nov13);
        assertThat(prime.isDirty()).isTrue();
        assertThat(prime.save()).isTrue();
        assertThat(prime.isDirty()).isFalse();

        PrimeMembers other = fresh();
        assertThat(other.of(ada)).contains(prime.of(ada).orElseThrow());
    }

    @Test
    @DisplayName("a hand-broken entry costs only that entry")
    void broken() throws Exception {
        UUID bo = UUID.randomUUID();
        Files.writeString(folder.resolve("prime.yml"), """
                players:
                  %s:
                    since: yesterday
                    until: 2026-12-01T00:00:00Z
                  %s:
                    since: 2026-11-01T00:00:00Z
                    until: 2026-12-01T00:00:00Z
                    months: [2026-11, nonsense]
                """.formatted(ada, bo));
        PrimeMembers prime = fresh();

        assertThat(prime.of(ada)).isEmpty();
        assertThat(prime.isPrime(bo, nov13)).isTrue();
        assertThat(prime.months(bo)).containsExactly(YearMonth.of(2026, 11));
    }
}
