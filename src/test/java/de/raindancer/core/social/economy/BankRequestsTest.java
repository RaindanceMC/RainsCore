package de.raindancer.core.social.economy;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class BankRequestsTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<Database> databases = new ArrayList<>();

    private BankRequests open() {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        return new BankRequests(database, clock::get);
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("a new request may go ahead once; its answer is what every repeat gets, after a restart too")
    void onceThenRemembered() {
        BankRequests bank = open();
        assertThat(bank.begin("r-1")).isInstanceOf(BankRequests.GoAhead.class);
        bank.finish("r-1", EconomyResult.Outcome.DONE, Money.of(700));

        BankRequests restarted = open();
        assertThat(restarted.begin("r-1")).isEqualTo(new BankRequests.Answered(EconomyResult.Outcome.DONE, Money.of(700)));
    }

    @Test
    @DisplayName("a request begun but never finished (the bank crashed mid-way) is never applied again")
    void unfinishedIsUncertain() {
        BankRequests bank = open();
        assertThat(bank.begin("r-2")).isInstanceOf(BankRequests.GoAhead.class);
        assertThat(open().begin("r-2")).isInstanceOf(BankRequests.Uncertain.class);
    }

    @Test
    @DisplayName("no request id, or one that is too long: refused, nothing moves")
    void badIds() {
        BankRequests bank = open();
        assertThat(bank.begin("")).isInstanceOf(BankRequests.Uncertain.class);
        assertThat(bank.begin("x".repeat(200))).isInstanceOf(BankRequests.Uncertain.class);
    }

    @Test
    @DisplayName("old answers are forgotten after a day, the rest kept")
    void prunes() {
        BankRequests bank = open();
        bank.begin("old");
        bank.finish("old", EconomyResult.Outcome.DONE, Money.of(1));
        clock.addAndGet(BankRequests.KEPT_MILLIS + 1);
        bank.begin("new");
        assertThat(bank.prune()).isEqualTo(1);
        assertThat(bank.begin("new")).isInstanceOf(BankRequests.Uncertain.class);
        assertThat(bank.begin("old")).isInstanceOf(BankRequests.GoAhead.class);
    }
}
