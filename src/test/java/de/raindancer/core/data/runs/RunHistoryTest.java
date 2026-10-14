package de.raindancer.core.data.runs;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.Tag("database")
class RunHistoryTest {

    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID SAM = UUID.randomUUID();
    private static final UUID KAI = UUID.randomUUID();

    @TempDir
    Path folder;
    private Database database;
    private RunHistory history;

    @BeforeEach
    void setUp() {
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE, () -> false);
        history = new RunHistory(database, "speedrun");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static Run solo(String category, UUID who, String name, int minutes, int at) {
        return Run.timed(category, Duration.ofMinutes(minutes)).player(who, name)
                .startedAt(Instant.ofEpochSecond(at)).build();
    }

    @Nested
    @DisplayName("ranking")
    class Ranking {

        @Test
        @DisplayName("best first; a tie goes to whoever did it first")
        void bestFirst() {
            history.add(solo("speedrun/any%", ALEX, "Alex", 30, 10));
            history.add(solo("speedrun/any%", SAM, "Sam", 25, 20));
            history.add(solo("speedrun/any%", KAI, "Kai", 25, 30));

            assertThat(history.leaderboard("speedrun/any%", RunHistory.Board.everyRun()))
                    .extracting(run -> run.players().get(run.players().keySet().iterator().next()))
                    .containsExactly("Sam", "Kai", "Alex");
        }

        @Test
        @DisplayName("one per player shows each player's best once")
        void onePerPlayer() {
            history.add(solo("speedrun/any%", ALEX, "Alex", 30, 10));
            history.add(solo("speedrun/any%", ALEX, "Alex", 20, 20));
            history.add(solo("speedrun/any%", SAM, "Sam", 25, 30));

            assertThat(history.leaderboard("speedrun/any%", RunHistory.Board.onePerPlayer()))
                    .extracting(Run::score).containsExactly(Duration.ofMinutes(20).toMillis(),
                            Duration.ofMinutes(25).toMillis());
            assertThat(history.placeOf(SAM, "speedrun/any%")).contains(2);
        }

        @Test
        @DisplayName("a higher-wins score ranks the other way")
        void higherWins() {
            history.add(Run.scored("manhunt/catches", 3, false).player(ALEX, "Alex").build());
            history.add(Run.scored("manhunt/catches", 7, false).player(SAM, "Sam").build());

            assertThat(history.record("manhunt/catches")).hasValueSatisfying(run ->
                    assertThat(run.includes(SAM)).isTrue());
        }

        @Test
        @DisplayName("modes of one plugin are kept apart by category, and listed by mode")
        void modes() {
            history.add(solo("speedrun/any%", ALEX, "Alex", 30, 10));
            history.add(solo("manhunt/1v3", ALEX, "Alex", 10, 20));
            history.add(solo("manhunt/1v3", SAM, "Sam", 12, 30));

            assertThat(history.record("speedrun/any%")).hasValueSatisfying(run ->
                    assertThat(run.score()).isEqualTo(Duration.ofMinutes(30).toMillis()));
            assertThat(history.categories("manhunt/")).containsExactly("manhunt/1v3");
            assertThat(history.categories()).containsExactly("manhunt/1v3", "speedrun/any%");
        }

        @Test
        @DisplayName("a struck run stays in the history but never counts as a best")
        void struck() {
            Run fast = solo("speedrun/any%", ALEX, "Alex", 5, 10);
            history.add(fast);
            history.add(solo("speedrun/any%", ALEX, "Alex", 30, 20));

            assertThat(history.rank(fast.id(), false)).isTrue();

            assertThat(history.personalBest(ALEX, "speedrun/any%")).hasValueSatisfying(run ->
                    assertThat(run.score()).isEqualTo(Duration.ofMinutes(30).toMillis()));
            assertThat(history.runsOf(ALEX)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("where a new run stands")
    class Standings {

        @Test
        @DisplayName("the first ranked run is a record and a personal best")
        void firstRun() {
            RunHistory.Standing how = history.add(solo("speedrun/any%", ALEX, "Alex", 30, 10));

            assertThat(how.newRecord()).isTrue();
            assertThat(how.isPersonalBest(ALEX)).isTrue();
            assertThat(how.rank()).isEqualTo(1);
        }

        @Test
        @DisplayName("against the record and each player's own best before it")
        void comparedToBefore() {
            history.add(solo("speedrun/any%", SAM, "Sam", 20, 10));
            history.add(solo("speedrun/any%", ALEX, "Alex", 30, 20));

            RunHistory.Standing how = history.add(solo("speedrun/any%", ALEX, "Alex", 25, 30));

            assertThat(how.newRecord()).isFalse();
            assertThat(how.isPersonalBest(ALEX)).isTrue();
            assertThat(how.rank()).isEqualTo(2);
            assertThat(how.versusRecord()).contains(Duration.ofMinutes(5).toMillis());
            assertThat(how.versusPersonalBest(ALEX)).contains(-Duration.ofMinutes(5).toMillis());
        }

        @Test
        @DisplayName("a practice run is never a record")
        void unranked() {
            RunHistory.Standing how = history.add(Run.timed("speedrun/any%", Duration.ofMinutes(1))
                    .player(ALEX, "Alex").unranked().build());

            assertThat(how.newRecord()).isFalse();
            assertThat(how.rank()).isZero();
            assertThat(history.record("speedrun/any%")).isEmpty();
        }
    }

    @Nested
    @DisplayName("kept")
    class Kept {

        @Test
        @DisplayName("everything about a run comes back after a restart, in this game only")
        void roundTrip() {
            Run run = Run.timed("manhunt/1v3", Duration.ofMinutes(12)).player(ALEX, "Alex").player(SAM, "Sam")
                    .split("nether", Duration.ofMinutes(4)).field("seed", 42L).build();
            history.add(run);
            new RunHistory(database, "hungergames").add(solo("hg/solo", KAI, "Kai", 9, 1));
            assertThat(history.flush()).isTrue();

            RunHistory reopened = new RunHistory(database, "speedrun");
            reopened.load();

            assertThat(reopened.byId(run.id())).contains(run);
            assertThat(reopened.size()).as("another game's runs are not this one's").isEqualTo(1);
            assertThat(reopened.bestSplit("manhunt/1v3", "nether")).contains(Duration.ofMinutes(4));
        }

        @Test
        @DisplayName("each run asks to be written as it is added")
        void writtenSoon() {
            List<Runnable> asked = new ArrayList<>();
            history.writeSoon(asked::add);

            history.add(solo("speedrun/any%", ALEX, "Alex", 30, 10));

            assertThat(asked).hasSize(1);
            asked.forEach(Runnable::run);
            assertThat(history.waiting()).isZero();
        }

        @Test
        @DisplayName("a database that cannot be read is never written over; runs wait in memory")
        void unreadableIsKept() throws Exception {
            Database unusable = TestDatabases.unusable(folder.resolve("broken.db"), CoreSchema.CORE);
            RunHistory kept = new RunHistory(unusable, "speedrun");
            kept.load();

            kept.add(solo("speedrun/any%", ALEX, "Alex", 30, 10));

            assertThat(kept.isWritable()).isFalse();
            assertThat(kept.flush()).isFalse();
            assertThat(kept.waiting()).isEqualTo(1);
            assertThat(kept.record("speedrun/any%")).isPresent();
            unusable.close();
            if (TestDatabases.dialect() == de.raindancer.core.data.sql.Dialect.SQLITE) {
                assertThat(Files.readString(folder.resolve("broken.db"))).isEqualTo("not a database".repeat(100));
            }
        }
    }
}
