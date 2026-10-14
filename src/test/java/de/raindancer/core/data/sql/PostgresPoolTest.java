package de.raindancer.core.data.sql;

import de.raindancer.core.testkit.TestPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresPoolTest {

    private static String searchPath(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); var rows = statement.executeQuery("SHOW search_path")) {
            rows.next();
            return rows.getString(1);
        }
    }

    @Test
    @DisplayName("a connection comes out set to the namespace asked for, and not auto-committing")
    void namespaceAndTransactions() throws SQLException {
        try (PostgresPool pool = PostgresPool.open(TestPostgres.require().withPoolSize(2))) {
            String namespace = TestPostgres.freshNamespace("pool");
            Connection connection = pool.borrow(namespace, 1_000);
            try {
                assertThat(searchPath(connection)).contains(namespace);
                assertThat(connection.getAutoCommit()).isFalse();
            } finally {
                pool.giveBack(connection);
            }
            Connection again = pool.borrow("public", 1_000);
            try {
                assertThat(searchPath(again)).doesNotContain(namespace);
            } finally {
                pool.giveBack(again);
            }
        }
    }

    @Test
    @DisplayName("never more connections than the pool size; the next borrower waits and then gives up")
    void bounded() throws Exception {
        try (PostgresPool pool = PostgresPool.open(TestPostgres.require().withPoolSize(2))) {
            Connection first = pool.borrow("public", 1_000);
            Connection second = pool.borrow("public", 1_000);
            long started = System.nanoTime();
            assertThatThrownBy(() -> pool.borrow("public", 200)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("came free");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(150);
            pool.giveBack(first);
            Connection third = pool.borrow("public", 1_000);
            assertThat(third).isNotNull();
            pool.giveBack(second);
            pool.giveBack(third);
            assertThat(pool.opened()).isLessThanOrEqualTo(2);
        }
    }

    @Test
    @DisplayName("work left uncommitted by a borrower is rolled back, not handed to the next one")
    void rolledBackOnReturn() throws SQLException {
        try (PostgresPool pool = PostgresPool.open(TestPostgres.require().withPoolSize(2))) {
            String namespace = TestPostgres.freshNamespace("rollback");
            Connection setup = pool.borrow("public", 1_000);
            try (Statement statement = setup.createStatement()) {
                statement.execute("CREATE SCHEMA " + namespace);
                statement.execute("CREATE TABLE " + namespace + ".t (n BIGINT)");
                setup.commit();
            } finally {
                pool.giveBack(setup);
            }
            Connection dirty = pool.borrow(namespace, 1_000);
            try (Statement statement = dirty.createStatement()) {
                statement.execute("INSERT INTO t VALUES (1)");
            } finally {
                pool.giveBack(dirty);
            }
            Connection clean = pool.borrow(namespace, 1_000);
            try (Statement statement = clean.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM t")) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            } finally {
                pool.giveBack(clean);
            }
        }
    }

    @Test
    @DisplayName("a connection that died while lent out is replaced, not handed out again")
    void brokenConnectionsAreReplaced() throws SQLException {
        try (PostgresPool pool = PostgresPool.open(TestPostgres.require().withPoolSize(2))) {
            Connection doomed = pool.borrow("public", 1_000);
            doomed.close();
            pool.giveBack(doomed);
            Connection next = pool.borrow("public", 1_000);
            try (Statement statement = next.createStatement(); var rows = statement.executeQuery("SELECT 1")) {
                assertThat(rows.next()).isTrue();
            } finally {
                pool.giveBack(next);
            }
        }
    }

    @Test
    @DisplayName("a connection the server killed is noticed when it is next borrowed")
    void killedByTheServer() throws SQLException {
        try (PostgresPool pool = PostgresPool.open(TestPostgres.require().withPoolSize(2).withApplicationName("kill-me-" + System.nanoTime()))) {
            Connection victim = pool.borrow("public", 1_000);
            int pid;
            try (Statement statement = victim.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                rows.next();
                pid = rows.getInt(1);
            }
            pool.giveBack(victim);
            try (Connection admin = TestPostgres.connect(); Statement statement = admin.createStatement()) {
                statement.execute("SELECT pg_terminate_backend(" + pid + ")");
            }
            pool.forceValidationOfIdle();
            Connection fresh = pool.borrow("public", 1_000);
            try (Statement statement = fresh.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                rows.next();
                assertThat(rows.getInt(1)).isNotEqualTo(pid);
            } finally {
                pool.giveBack(fresh);
            }
        }
    }

    @Test
    @DisplayName("many threads borrowing at once each get a connection of their own")
    void concurrent() throws Exception {
        try (PostgresPool pool = PostgresPool.open(TestPostgres.require().withPoolSize(4))) {
            ExecutorService threads = Executors.newFixedThreadPool(16);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger inUse = new AtomicInteger();
            AtomicInteger most = new AtomicInteger();
            AtomicInteger done = new AtomicInteger();
            List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int at = 0; at < 64; at++) {
                futures.add(threads.submit(() -> {
                    try {
                        go.await();
                        Connection connection = pool.borrow("public", 10_000);
                        try {
                            most.accumulateAndGet(inUse.incrementAndGet(), Math::max);
                            try (Statement statement = connection.createStatement()) {
                                statement.execute("SELECT pg_sleep(0.005)");
                            }
                            inUse.decrementAndGet();
                        } finally {
                            pool.giveBack(connection);
                        }
                        done.incrementAndGet();
                    } catch (Throwable failed) {
                        failures.add(failed);
                    }
                }));
            }
            go.countDown();
            for (var future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            threads.shutdownNow();
            assertThat(failures).isEmpty();
            assertThat(done.get()).isEqualTo(64);
            assertThat(most.get()).isLessThanOrEqualTo(4);
        }
    }

    @Test
    @DisplayName("an unreachable database is reported as unusable rather than hanging the caller")
    void unreachable() {
        PostgresSettings nowhere = PostgresSettings.of("127.0.0.1", 1, "nope", "nobody", "");
        try (PostgresPool pool = PostgresPool.open(nowhere)) {
            assertThat(pool.isReachable()).isFalse();
            assertThatThrownBy(() -> pool.borrow("public", 500)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("a namespace that is not a plain lower-case name is refused, it is spliced into SQL")
    void namespaceNamesAreChecked() {
        try (PostgresPool pool = PostgresPool.open(TestPostgres.require().withPoolSize(2))) {
            assertThatThrownBy(() -> pool.borrow("x; DROP SCHEMA public", 500)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> pool.borrow("Upper", 500)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> pool.borrow("", 500)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("after close nothing is lent out")
    void closed() {
        PostgresPool pool = PostgresPool.open(TestPostgres.require().withPoolSize(2));
        pool.close();
        assertThatThrownBy(() -> pool.borrow("public", 200)).isInstanceOf(SQLException.class);
        pool.close();
    }
}
