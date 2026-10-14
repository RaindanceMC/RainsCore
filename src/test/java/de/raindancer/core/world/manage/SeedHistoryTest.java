package de.raindancer.core.world.manage;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every seed a world has had, kept in the real {@code core.db} schema rather than a stand-in, so the
 * table these tests read is the table a server gets.
 */
@org.junit.jupiter.api.Tag("database")
class SeedHistoryTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000L);
    private Database database;

    private SeedHistory open() {
        if (database != null) {
            database.close();
        }
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE, () -> false);
        SeedHistory history = new SeedHistory(database, clock::get);
        history.load();
        return history;
    }

    @AfterEach
    void close() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("an unknown world has no history, rather than an error")
    void emptyToBeginWith() {
        SeedHistory history = open();

        assertThat(history.of("farm")).isEmpty();
        assertThat(history.latest("farm")).isEmpty();
        assertThat(history.worlds()).isEmpty();
    }

    @Test
    @DisplayName("newest first, with when and why each seed was recorded")
    void newestFirst() {
        SeedHistory history = open();
        history.record("farm", 1L, SeedHistory.Cause.CREATED);
        clock.set(2_000L);
        history.record("farm", 2L, SeedHistory.Cause.REGENERATED);

        assertThat(history.of("farm")).containsExactly(
                new SeedHistory.Entry("farm", 2L, 2_000L, SeedHistory.Cause.REGENERATED),
                new SeedHistory.Entry("farm", 1L, 1_000L, SeedHistory.Cause.CREATED));
        assertThat(history.latest("farm")).hasValue(2L);
    }

    @Test
    @DisplayName("worlds are kept apart, and matched regardless of case like Bukkit matches them")
    void perWorld() {
        SeedHistory history = open();
        history.record("farm", 1L, SeedHistory.Cause.CREATED);
        history.record("Build", 9L, SeedHistory.Cause.CREATED);

        assertThat(history.of("FARM")).extracting(SeedHistory.Entry::seed).containsExactly(1L);
        assertThat(history.of("build")).extracting(SeedHistory.Entry::seed).containsExactly(9L);
        assertThat(history.worlds()).containsExactlyInAnyOrder("farm", "Build");
    }

    @Test
    @DisplayName("distinct seeds: a seed used twice is listed once, at the last time it was used")
    void distinctSeeds() {
        SeedHistory history = open();
        history.record("farm", 1L, SeedHistory.Cause.CREATED);
        clock.set(2_000L);
        history.record("farm", 2L, SeedHistory.Cause.REGENERATED);
        clock.set(3_000L);
        history.record("farm", 1L, SeedHistory.Cause.REGENERATED);

        assertThat(history.seeds("farm")).extracting(SeedHistory.Entry::seed, SeedHistory.Entry::at)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, 3_000L),
                        org.assertj.core.groups.Tuple.tuple(2L, 2_000L));
    }

    @Test
    @DisplayName("what was recorded survives a flush and a restart")
    void survivesARestart() {
        SeedHistory history = open();
        history.record("farm", 5L, SeedHistory.Cause.REPLACED);
        assertThat(history.isDirty()).isTrue();
        assertThat(history.flush()).isTrue();
        assertThat(history.isDirty()).isFalse();

        SeedHistory reopened = open();

        assertThat(reopened.of("farm")).containsExactly(
                new SeedHistory.Entry("farm", 5L, 1_000L, SeedHistory.Cause.REPLACED));
    }

    @Test
    @DisplayName("a recorded seed asks to be written soon, rather than waiting for the timer or the shutdown")
    void flushesSoon() {
        java.util.List<Runnable> asked = new java.util.ArrayList<>();
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE, () -> false);
        SeedHistory history = new SeedHistory(database, clock::get, asked::add);
        history.load();

        history.record("farm", 9L, SeedHistory.Cause.CREATED);

        // Written at shutdown instead, it is a database write on the thread running the world — the
        // live test server logged exactly that as an error on its first stop.
        assertThat(asked).hasSize(1);
        asked.getFirst().run();
        assertThat(history.isDirty()).isFalse();
    }

    @Test
    @DisplayName("flushNow answers once what was recorded is on disk, on the writer it was handed")
    void flushNowWaits() {
        java.util.List<Runnable> asked = new java.util.ArrayList<>();
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE, () -> false);
        SeedHistory history = new SeedHistory(database, clock::get, asked::add);
        history.load();
        history.record("farm", 3L, SeedHistory.Cause.REPLACED);
        asked.clear();

        java.util.concurrent.CompletableFuture<Boolean> done = history.flushNow();

        assertThat(done).isNotDone();
        asked.forEach(Runnable::run);
        assertThat(done).isCompletedWithValue(true);
        assertThat(history.isDirty()).isFalse();
    }

    @Test
    @DisplayName("nothing to write is not a failure, and a blank world name records nothing")
    void edges() {
        SeedHistory history = open();

        assertThat(history.flush()).isTrue();
        assertThat(history.record(" ", 1L, SeedHistory.Cause.CREATED)).isFalse();
        assertThat(history.record(null, 1L, SeedHistory.Cause.CREATED)).isFalse();
        assertThat(history.isDirty()).isFalse();
    }

    @Test
    @DisplayName("a flush does not answer 'written' while an earlier flush still has the seed in hand")
    void aFlushWaitsForTheOneAlreadyWriting() throws Exception {
        SeedHistory history = open();
        history.record("farm", 7L, SeedHistory.Cause.REPLACED);

        // Somebody else holds the write lock, so the first flush takes the seed off the queue and
        // then has to wait for its turn.
        AutoCloseable held = TestDatabases.holdWrites(folder.resolve("core.db"), "world_seed");
        {
            java.util.concurrent.CompletableFuture<Boolean> first =
                    java.util.concurrent.CompletableFuture.supplyAsync(history::flush);
            long until = System.currentTimeMillis() + 5_000;
            while (history.isDirty() && System.currentTimeMillis() < until) {
                Thread.sleep(5);
            }
            assertThat(history.isDirty()).as("the first flush took the seed").isFalse();

            java.util.concurrent.CompletableFuture<Boolean> second =
                    java.util.concurrent.CompletableFuture.supplyAsync(history::flush);
            Thread.sleep(300);
            assertThat(second)
                    .as("answering true here is what lets a world be deleted before its seed is on "
                            + "disk anywhere")
                    .isNotDone();

            held.close();
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(open().of("farm")).extracting(SeedHistory.Entry::seed).containsExactly(7L);
    }
}
