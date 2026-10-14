package de.raindancer.core.content.achievement;

import de.raindancer.core.testkit.TestDatabases;

import org.bukkit.Material;
import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Custom achievements: something a player did that is worth telling them about.
 *
 * <h2>Why not vanilla advancements</h2>
 * They were considered. A vanilla advancement is a datapack JSON file, keyed to vanilla triggers,
 * which means "claim your first plot" or "fly a ghast to every stop" cannot be expressed at all —
 * there is no trigger for either. Writing the datapack from the server would also mean regenerating
 * and reloading it whenever a plugin added one. So these are the plugin's own, and the toast that
 * announces them is a message rather than the vanilla popup.
 *
 * <h2>What matters here</h2>
 * That an achievement is granted <em>once</em>. A player who claims their second plot has not
 * earned "your first claim" again, and a listener firing twice must not announce it twice — which
 * is the bug every hand-rolled version of this has.
 */
@org.junit.jupiter.api.Tag("database")
class AchievementsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private Database openedDatabase;

    /** One database per test, opened on first use so the temporary directory already exists. */
    private Database database() {
        if (openedDatabase == null || !openedDatabase.isUsable()) {
            openedDatabase = TestDatabases.open(directory.resolve("core.db"), CoreSchema.CORE,
                    () -> false);
        }
        return openedDatabase;
    }

    @AfterEach
    void closeDatabase() {
        if (openedDatabase != null) {
            openedDatabase.close();
        }
    }

    @TempDir
    Path directory;
    private AtomicLong clock;
    private Achievements achievements;
    private List<Awarded> announced;

    private record Awarded(UUID player, String key) {
    }

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000_000L);
        announced = new ArrayList<>();
        achievements = new Achievements(directory.resolve("achievements.yml"), database(),
                clock::get);
        achievements.onEarned((player, achievement) ->
                announced.add(new Awarded(player, achievement.key())));
    }

    private static Achievement firstClaim() {
        return Achievement.builder("claims", "first-claim")
                .title("<gold>Landowner")
                .description("Claim your first plot")
                .icon(Material.GRASS_BLOCK)
                .points(10)
                .build();
    }

    // ------------------------------------------------------------------ defining

    @Nested
    @DisplayName("defining one")
    class Defining {

        @Test
        @DisplayName("it can be found by its key")
        void defines() {
            achievements.define(firstClaim());
            assertThat(achievements.byKey("claims:first-claim")).contains(firstClaim());
        }

        @Test
        @DisplayName("the key is namespaced, so two plugins cannot collide")
        void keysAreNamespaced() {
            assertThat(firstClaim().key()).isEqualTo("claims:first-claim");
        }

        @Test
        @DisplayName("a plugin's default does not overwrite what the owner has edited")
        void defaultsDoNotOverwriteEdits() {
            achievements.define(firstClaim().withTitle("<red>Renamed by the owner"));
            achievements.defineIfAbsent(firstClaim());
            assertThat(achievements.byKey("claims:first-claim").orElseThrow().title())
                    .isEqualTo("<red>Renamed by the owner");
        }

        @Test
        @DisplayName("everything one plugin defines can be listed")
        void listsByPlugin() {
            achievements.define(firstClaim());
            achievements.define(Achievement.builder("ghasts", "first-flight")
                    .title("<aqua>Airborne").description("Take your first flight").build());

            assertThat(achievements.ofPlugin("claims")).hasSize(1);
            assertThat(achievements.all()).hasSize(2);
        }

        @Test
        @DisplayName("a hidden one is not shown until it is earned")
        void hiddenOnes() {
            Achievement secret = Achievement.builder("claims", "secret")
                    .title("<gold>?").description("Find out").hidden(true).build();
            achievements.define(secret);
            achievements.define(firstClaim());

            assertThat(achievements.visibleTo(ALICE)).extracting(Achievement::key)
                    .containsExactly("claims:first-claim");

            achievements.award(ALICE, "claims:secret");

            assertThat(achievements.visibleTo(ALICE)).extracting(Achievement::key)
                    .containsExactlyInAnyOrder("claims:first-claim", "claims:secret");
        }

        @Test
        @DisplayName("it needs a plugin, an id and a title")
        void refusesIncomplete() {
            assertThatCode(() -> Achievement.builder(null, "x").title("<gold>x").build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> Achievement.builder("claims", " ").title("<gold>x").build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> Achievement.builder("claims", "x").build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------ awarding

    @Nested
    @DisplayName("earning one")
    class Awarding {

        @BeforeEach
        void define() {
            achievements.define(firstClaim());
        }

        @Test
        @DisplayName("a player who earns it has it")
        void awards() {
            assertThat(achievements.award(ALICE, "claims:first-claim")).isTrue();
            assertThat(achievements.hasEarned(ALICE, "claims:first-claim")).isTrue();
            assertThat(achievements.hasEarned(BOB, "claims:first-claim")).isFalse();
        }

        /** The bug every hand-rolled version of this has. */
        @Test
        @DisplayName("earning it twice only counts once, and only announces once")
        void awardsOnlyOnce() {
            assertThat(achievements.award(ALICE, "claims:first-claim")).isTrue();
            assertThat(achievements.award(ALICE, "claims:first-claim"))
                    .as("claiming a second plot has not earned 'your first claim' again")
                    .isFalse();

            assertThat(announced).hasSize(1);
            assertThat(achievements.earnedBy(ALICE)).hasSize(1);
        }

        @Test
        @DisplayName("whoever wants to announce it is told, once, with the achievement")
        void notifiesListeners() {
            achievements.award(ALICE, "claims:first-claim");
            assertThat(announced).containsExactly(new Awarded(ALICE, "claims:first-claim"));
        }

        @Test
        @DisplayName("a listener that throws does not stop the award or the other listeners")
        void survivesABrokenListener() {
            achievements.onEarned((player, achievement) -> {
                throw new IllegalStateException("no");
            });
            List<String> second = new ArrayList<>();
            achievements.onEarned((player, achievement) -> second.add(achievement.key()));

            assertThat(achievements.award(ALICE, "claims:first-claim")).isTrue();
            assertThat(second).containsExactly("claims:first-claim");
            assertThat(achievements.hasEarned(ALICE, "claims:first-claim")).isTrue();
        }

        @Test
        @DisplayName("awarding one nobody defined does nothing rather than inventing it")
        void refusesUndefined() {
            assertThat(achievements.award(ALICE, "claims:nothing-like-this")).isFalse();
            assertThat(achievements.earnedBy(ALICE)).isEmpty();
            assertThat(announced).isEmpty();
        }

        @Test
        @DisplayName("when it was earned is remembered")
        void remembersWhen() {
            achievements.award(ALICE, "claims:first-claim");
            assertThat(achievements.earnedAt(ALICE, "claims:first-claim"))
                    .isPresent()
                    .hasValueSatisfying(when ->
                            assertThat(when.toEpochMilli()).isEqualTo(1_000_000L));
        }

        @Test
        @DisplayName("it can be taken back, for a mistake")
        void revokes() {
            achievements.award(ALICE, "claims:first-claim");
            assertThat(achievements.revoke(ALICE, "claims:first-claim")).isTrue();
            assertThat(achievements.hasEarned(ALICE, "claims:first-claim")).isFalse();
            assertThat(achievements.revoke(ALICE, "claims:first-claim")).isFalse();
        }

        @Test
        @DisplayName("nulls do not throw")
        void survivesNulls() {
            assertThatCode(() -> {
                achievements.award(null, "claims:first-claim");
                achievements.award(ALICE, null);
                achievements.hasEarned(null, null);
                achievements.earnedBy(null);
            }).doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------ progress

    /**
     * The other half of an achievement worth having: one you work towards. "Visit every stop" is
     * only interesting if a player can see they are at four of nine.
     */
    @Nested
    @DisplayName("one you work towards")
    class Progress {

        @BeforeEach
        void define() {
            achievements.define(Achievement.builder("ghasts", "every-stop")
                    .title("<aqua>Well travelled")
                    .description("Visit every stop on the network")
                    .goal(9)
                    .build());
        }

        @Test
        @DisplayName("progress adds up and is remembered")
        void countsUp() {
            achievements.progress(ALICE, "ghasts:every-stop", 4);
            assertThat(achievements.progressOf(ALICE, "ghasts:every-stop")).isEqualTo(4);
            achievements.progress(ALICE, "ghasts:every-stop", 2);
            assertThat(achievements.progressOf(ALICE, "ghasts:every-stop")).isEqualTo(6);
        }

        @Test
        @DisplayName("reaching the goal earns it, once")
        void earnsOnCompletion() {
            achievements.progress(ALICE, "ghasts:every-stop", 8);
            assertThat(achievements.hasEarned(ALICE, "ghasts:every-stop")).isFalse();

            achievements.progress(ALICE, "ghasts:every-stop", 1);
            assertThat(achievements.hasEarned(ALICE, "ghasts:every-stop")).isTrue();
            assertThat(announced).hasSize(1);

            achievements.progress(ALICE, "ghasts:every-stop", 5);
            assertThat(announced)
                    .as("going past the goal must not announce it again")
                    .hasSize(1);
        }

        @Test
        @DisplayName("progress can be set outright, not only added to")
        void setsProgress() {
            achievements.setProgress(ALICE, "ghasts:every-stop", 9);
            assertThat(achievements.hasEarned(ALICE, "ghasts:every-stop")).isTrue();
        }

        @Test
        @DisplayName("an achievement with no goal is simply earned or not")
        void noGoalMeansNoProgress() {
            achievements.define(firstClaim());
            assertThat(firstClaim().goal()).isEmpty();
            achievements.progress(ALICE, "claims:first-claim", 1);
            assertThat(achievements.hasEarned(ALICE, "claims:first-claim"))
                    .as("any progress at all completes something with no goal")
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------ scores

    @Test
    @DisplayName("a player's points are what their achievements are worth")
    void addsUpPoints() {
        achievements.define(firstClaim());
        achievements.define(Achievement.builder("ghasts", "first-flight")
                .title("<aqua>Airborne").description("Fly").points(5).build());

        achievements.award(ALICE, "claims:first-claim");
        achievements.award(ALICE, "ghasts:first-flight");

        assertThat(achievements.pointsOf(ALICE)).isEqualTo(15);
        assertThat(achievements.pointsOf(BOB)).isZero();
    }

    // ------------------------------------------------------------------ persistence

    @Nested
    @DisplayName("across a restart")
    class Persistence {

        @Test
        @DisplayName("definitions, awards and progress all survive")
        void roundTrips() {
            achievements.define(firstClaim());
            achievements.define(Achievement.builder("ghasts", "every-stop")
                    .title("<aqua>Well travelled").description("Visit every stop").goal(9).build());
            achievements.award(ALICE, "claims:first-claim");
            achievements.progress(ALICE, "ghasts:every-stop", 4);
            achievements.flush();

            // Closed and reopened over the same file and the same database, because that is what a
            // restart is — and this store has two halves, so both have to survive it.
            openedDatabase.close();
            Achievements reopened = new Achievements(directory.resolve("achievements.yml"),
                    database(), clock::get);
            reopened.load();

            assertThat(reopened.all()).hasSize(2);
            assertThat(reopened.hasEarned(ALICE, "claims:first-claim")).isTrue();
            assertThat(reopened.progressOf(ALICE, "ghasts:every-stop")).isEqualTo(4);
            assertThat(reopened.byKey("claims:first-claim").orElseThrow().points()).isEqualTo(10);
        }

        @Test
        @DisplayName("a missing achievements.yml does not cost anybody what they earned")
        void earnedSurvivesAMissingDefinitionsFile() throws Exception {
            achievements.define(firstClaim());
            achievements.award(ALICE, "claims:first-claim");
            achievements.flush();
            java.nio.file.Files.delete(directory.resolve("achievements.yml"));

            // A restore, or a deploy that did not carry the file: the definitions are gone, the
            // database is not. Before, nobody's awards were loaded, and the next flush after one new
            // award wrote the player's rows back without the old ones.
            openedDatabase.close();
            Achievements reopened = new Achievements(directory.resolve("achievements.yml"), database(), clock::get);
            reopened.load();
            reopened.define(firstClaim());
            reopened.define(Achievement.builder("ghasts", "first-flight").title("<aqua>Up").description("Fly").build());
            assertThat(reopened.hasEarned(ALICE, "claims:first-claim")).isTrue();
            reopened.award(ALICE, "ghasts:first-flight");
            reopened.flush();

            openedDatabase.close();
            Achievements again = new Achievements(directory.resolve("achievements.yml"), database(), clock::get);
            again.load();
            assertThat(again.hasEarned(ALICE, "claims:first-claim")).isTrue();
            assertThat(again.hasEarned(ALICE, "ghasts:first-flight")).isTrue();
        }

        @Test
        @DisplayName("a missing file is simply nothing earned yet")
        void survivesAMissingFile() {
            Achievements fresh = new Achievements(directory.resolve("nothing.yml"),
                    TestDatabases.open(directory.resolve("never-used.db"), CoreSchema.CORE, () -> false),
                    clock::get);
            assertThatCode(fresh::load).doesNotThrowAnyException();
            assertThat(fresh.all()).isEmpty();
        }

        @Test
        @DisplayName("nothing is written when nothing changed")
        void doesNotWriteWithoutChanges() {
            achievements.load();
            achievements.flush();
            assertThat(directory.resolve("achievements.yml")).doesNotExist();
        }
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("eight threads awarding the same achievement award it exactly once")
    void isSafeFromEveryThread() throws Exception {
        achievements.define(firstClaim());
        AtomicInteger won = new AtomicInteger();
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int thread = 0; thread < threads; thread++) {
                pool.submit(() -> {
                    go.await();
                    if (achievements.award(ALICE, "claims:first-claim")) {
                        won.incrementAndGet();
                    }
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(won.get())
                .as("two listeners firing at once must not announce it twice")
                .isEqualTo(1);
        assertThat(announced).hasSize(1);
    }

    @Test
    @DisplayName("looking somebody up, or revoking what they never earned, leaves nothing behind")
    void readingCreatesNothing() {
        UUID nobody = UUID.randomUUID();

        assertThat(achievements.earnedBy(nobody)).isEmpty();
        assertThat(achievements.hasEarned(nobody, "claims:first")).isFalse();
        assertThat(achievements.revoke(nobody, "claims:first")).isFalse();
        assertThat(achievements.pointsOf(nobody)).isZero();
    }

    @Test
    @DisplayName("an id with a dot in it comes back from the file as it went in")
    void dottedIdsSurvive() {
        Achievement dotted = Achievement.builder("claims", "season.2").title("<gold>Season two")
                .icon(Material.GRASS_BLOCK).build();
        achievements.define(dotted);
        achievements.flush();

        Achievements reopened = new Achievements(directory.resolve("achievements.yml"), database(),
                clock::get);
        reopened.load();

        assertThat(reopened.byKey("claims:season.2")).contains(dotted);
    }
}
