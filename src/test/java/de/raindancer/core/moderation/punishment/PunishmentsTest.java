package de.raindancer.core.moderation.punishment;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Bans, mutes and the record of who did what.
 *
 * <h2>Why a plugin should not write its own</h2>
 * Every plugin that can refuse a player something eventually grows a way of remembering that it
 * refused them. Done separately, that means several files that disagree about whether somebody is
 * muted, several answers to "when does this expire", and no single place to look when a player asks
 * why they cannot build. One system, and any plugin can ask.
 *
 * <h2>What a punishment is</h2>
 * A record that a moderator did something, with a reason and — usually — an end. It is never deleted:
 * lifting a ban <em>adds</em> the lifting, so the history still says what happened. That is the
 * difference between a moderation system and a set of flags.
 */
@org.junit.jupiter.api.Tag("database")
class PunishmentsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final UUID MOD = UUID.nameUUIDFromBytes("mod".getBytes());

    @TempDir
    Path directory;
    private AtomicLong clock;
    private Punishments punishments;
    /**
     * The real engine, not a stand-in.
     *
     * <p>These punishments live in SQLite now, and the questions worth testing are its questions:
     * whether a lift survives being written and read back, whether a time column that is absent
     * comes back as absent rather than as 1970. A fake would answer both the way we expect.
     */
    private Database database;

    private Database openDatabase() {
        return TestDatabases.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
    }

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000_000L);
        database = openDatabase();
        punishments = new Punishments(database, clock::get);
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    private void advance(Duration by) {
        clock.addAndGet(by.toMillis());
    }

    // ------------------------------------------------------------------ handing them out

    @Nested
    @DisplayName("punishing somebody")
    class Punishing {

        @Test
        @DisplayName("a ban makes them banned")
        void bans() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "griefing", Duration.ofDays(7));
            assertThat(punishments.isActive(ALICE, PunishmentKind.BAN)).isTrue();
            assertThat(punishments.isActive(BOB, PunishmentKind.BAN)).isFalse();
        }

        @Test
        @DisplayName("a mute does not ban them, and the other way round")
        void kindsAreIndependent() {
            punishments.punish(ALICE, PunishmentKind.MUTE, MOD, "spam", Duration.ofHours(1));
            assertThat(punishments.isActive(ALICE, PunishmentKind.MUTE)).isTrue();
            assertThat(punishments.isActive(ALICE, PunishmentKind.BAN)).isFalse();
        }

        @Test
        @DisplayName("a punishment remembers who, why, and when it ends")
        void remembersTheDetails() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "griefing", Duration.ofDays(7));

            Punishment active = punishments.active(ALICE, PunishmentKind.BAN).orElseThrow();
            assertThat(active.target()).isEqualTo(ALICE);
            assertThat(active.moderator()).isEqualTo(MOD);
            assertThat(active.reason()).isEqualTo("griefing");
            assertThat(active.kind()).isEqualTo(PunishmentKind.BAN);
            assertThat(active.isPermanent()).isFalse();
        }

        @Test
        @DisplayName("a punishment with no duration never ends")
        void permanentPunishments() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "never coming back", null);
            advance(Duration.ofDays(3650));
            assertThat(punishments.isActive(ALICE, PunishmentKind.BAN)).isTrue();
            assertThat(punishments.active(ALICE, PunishmentKind.BAN).orElseThrow().isPermanent())
                    .isTrue();
        }

        @Test
        @DisplayName("a kick is over the moment it happens, but is still recorded")
        void kicksAreRecordedButNotActive() {
            punishments.punish(ALICE, PunishmentKind.KICK, MOD, "language", null);
            assertThat(punishments.isActive(ALICE, PunishmentKind.KICK))
                    .as("a kick is a thing that happened, not a state somebody is in")
                    .isFalse();
            assertThat(punishments.history(ALICE)).hasSize(1);
        }

        @Test
        @DisplayName("punishing again while already punished replaces the old one")
        void replacesAnActiveOne() {
            punishments.punish(ALICE, PunishmentKind.MUTE, MOD, "spam", Duration.ofMinutes(10));
            punishments.punish(ALICE, PunishmentKind.MUTE, MOD, "still spamming", Duration.ofDays(1));

            assertThat(punishments.active(ALICE, PunishmentKind.MUTE).orElseThrow().reason())
                    .isEqualTo("still spamming");
            assertThat(punishments.history(ALICE))
                    .as("the first one still happened and the history should say so")
                    .hasSize(2);
        }
    }

    // ------------------------------------------------------------------ time

    @Nested
    @DisplayName("expiry")
    class Expiry {

        @Test
        @DisplayName("a punishment stops applying once its time is up")
        void expires() {
            punishments.punish(ALICE, PunishmentKind.MUTE, MOD, "spam", Duration.ofMinutes(10));
            advance(Duration.ofMinutes(11));
            assertThat(punishments.isActive(ALICE, PunishmentKind.MUTE)).isFalse();
        }

        @Test
        @DisplayName("it applies right up until then")
        void livesUntilItDoesNot() {
            punishments.punish(ALICE, PunishmentKind.MUTE, MOD, "spam", Duration.ofMinutes(10));
            advance(Duration.ofMinutes(10).minusMillis(1));
            assertThat(punishments.isActive(ALICE, PunishmentKind.MUTE)).isTrue();
        }

        @Test
        @DisplayName("how long is left can be asked, for telling the player")
        void reportsTimeLeft() {
            punishments.punish(ALICE, PunishmentKind.MUTE, MOD, "spam", Duration.ofMinutes(10));
            advance(Duration.ofMinutes(4));
            assertThat(punishments.remaining(ALICE, PunishmentKind.MUTE))
                    .contains(Duration.ofMinutes(6));
        }

        @Test
        @DisplayName("an expired punishment stays in the history")
        void expiredOnesAreStillRecorded() {
            punishments.punish(ALICE, PunishmentKind.MUTE, MOD, "spam", Duration.ofMinutes(10));
            advance(Duration.ofMinutes(11));
            assertThat(punishments.history(ALICE)).hasSize(1);
        }
    }

    // ------------------------------------------------------------------ lifting

    @Nested
    @DisplayName("lifting a punishment")
    class Lifting {

        @Test
        @DisplayName("makes it stop applying")
        void lifts() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "griefing", Duration.ofDays(7));
            assertThat(punishments.lift(ALICE, PunishmentKind.BAN, BOB, "appealed")).isTrue();
            assertThat(punishments.isActive(ALICE, PunishmentKind.BAN)).isFalse();
        }

        /**
         * The difference between a moderation system and a set of flags: the history still says the
         * ban happened, who gave it, who lifted it and why.
         */
        @Test
        @DisplayName("does not erase it — the history still says it happened")
        void keepsTheRecord() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "griefing", Duration.ofDays(7));
            punishments.lift(ALICE, PunishmentKind.BAN, BOB, "appealed");

            List<Punishment> history = punishments.history(ALICE);
            assertThat(history).hasSize(1);
            Punishment ban = history.getFirst();
            assertThat(ban.reason()).isEqualTo("griefing");
            assertThat(ban.liftedBy()).contains(BOB);
            assertThat(ban.liftReason()).contains("appealed");
        }

        @Test
        @DisplayName("lifting something that is not there says so")
        void liftingNothing() {
            assertThat(punishments.lift(ALICE, PunishmentKind.BAN, BOB, "nothing to lift")).isFalse();
        }

        @Test
        @DisplayName("lifting twice only counts once")
        void liftingTwice() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "griefing", null);
            assertThat(punishments.lift(ALICE, PunishmentKind.BAN, BOB, "appealed")).isTrue();
            assertThat(punishments.lift(ALICE, PunishmentKind.BAN, BOB, "again")).isFalse();
        }
    }

    // ------------------------------------------------------------------ the record

    @Nested
    @DisplayName("the history")
    class History {

        @Test
        @DisplayName("is newest first, because that is what a moderator is looking for")
        void newestFirst() {
            punishments.punish(ALICE, PunishmentKind.MUTE, MOD, "first", Duration.ofMinutes(1));
            advance(Duration.ofMinutes(5));
            punishments.punish(ALICE, PunishmentKind.MUTE, MOD, "second", Duration.ofMinutes(1));

            assertThat(punishments.history(ALICE)).extracting(Punishment::reason)
                    .containsExactly("second", "first");
        }

        @Test
        @DisplayName("is per player")
        void isPerPlayer() {
            punishments.punish(ALICE, PunishmentKind.MUTE, MOD, "alice", null);
            punishments.punish(BOB, PunishmentKind.MUTE, MOD, "bob", null);

            assertThat(punishments.history(ALICE)).extracting(Punishment::reason)
                    .containsExactly("alice");
        }

        @Test
        @DisplayName("everything currently in force can be listed, for a moderator's screen")
        void listsActiveOnes() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "griefing", null);
            punishments.punish(BOB, PunishmentKind.MUTE, MOD, "spam", Duration.ofMinutes(10));
            punishments.punish(BOB, PunishmentKind.KICK, MOD, "language", null);

            assertThat(punishments.allActive()).hasSize(2);
            assertThat(punishments.allActive(PunishmentKind.BAN)).hasSize(1);
        }

        @Test
        @DisplayName("somebody with a clean record has an empty one, not a null")
        void cleanRecord() {
            assertThat(punishments.history(ALICE)).isEmpty();
            assertThat(punishments.active(ALICE, PunishmentKind.BAN)).isEmpty();
            assertThat(punishments.remaining(ALICE, PunishmentKind.BAN)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ persistence

    @Nested
    @DisplayName("across a restart")
    class Persistence {

        @Test
        @DisplayName("an active ban is still active")
        void roundTrips() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "griefing", Duration.ofDays(7));
            punishments.punish(BOB, PunishmentKind.MUTE, MOD, "spam", null);
            punishments.lift(BOB, PunishmentKind.MUTE, MOD, "sorry");
            punishments.flush();

            // Closed and reopened over the same file, because that is what a restart is: a
            // connection that stayed open would prove only that the in-memory copy is still there.
            database.close();
            database = openDatabase();
            Punishments reopened = new Punishments(database, clock::get);
            reopened.load();

            assertThat(reopened.isActive(ALICE, PunishmentKind.BAN)).isTrue();
            assertThat(reopened.active(ALICE, PunishmentKind.BAN).orElseThrow().reason())
                    .isEqualTo("griefing");
            assertThat(reopened.isActive(BOB, PunishmentKind.MUTE)).isFalse();
            assertThat(reopened.history(BOB).getFirst().liftedBy()).contains(MOD);
        }

        @Test
        @DisplayName("a ban that expired while the server was down is not active when it comes back")
        void expiryIsAbsolute() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "a week", Duration.ofDays(7));
            punishments.flush();
            advance(Duration.ofDays(8));

            // Closed and reopened over the same file, because that is what a restart is: a
            // connection that stayed open would prove only that the in-memory copy is still there.
            database.close();
            database = openDatabase();
            Punishments reopened = new Punishments(database, clock::get);
            reopened.load();

            assertThat(reopened.isActive(ALICE, PunishmentKind.BAN))
                    .as("a ban ends when it said it would, not a week after the next restart")
                    .isFalse();
        }

        @Test
        @DisplayName("a missing file is a clean server")
        void survivesAMissingFile() {
            Database empty = TestDatabases.open(directory.resolve("never-used.db"), CoreSchema.CORE,
                    () -> false);
            Punishments fresh = new Punishments(empty, clock::get);
            assertThatCode(fresh::load).doesNotThrowAnyException();
            assertThat(fresh.allActive()).isEmpty();
            empty.close();
        }
    }

    // ------------------------------------------------------------------ how long is that

    @Nested
    @DisplayName("reading a duration somebody typed")
    class ReadingDurations {

        @Test
        @DisplayName("the forms people actually type")
        void parsesTheUsualForms() {
            assertThat(Durations.parse("30m")).contains(Duration.ofMinutes(30));
            assertThat(Durations.parse("2h")).contains(Duration.ofHours(2));
            assertThat(Durations.parse("7d")).contains(Duration.ofDays(7));
            assertThat(Durations.parse("45s")).contains(Duration.ofSeconds(45));
            assertThat(Durations.parse("3w")).contains(Duration.ofDays(21));
        }

        @Test
        @DisplayName("several parts add up, so 1d12h is a day and a half")
        void addsUpParts() {
            assertThat(Durations.parse("1d12h")).contains(Duration.ofHours(36));
            assertThat(Durations.parse("1h30m")).contains(Duration.ofMinutes(90));
        }

        @Test
        @DisplayName("for ever is a word, not a very large number")
        void understandsPermanent() {
            assertThat(Durations.parse("perm")).isEmpty();
            assertThat(Durations.parse("permanent")).isEmpty();
            assertThat(Durations.parse("forever")).isEmpty();
        }

        @Test
        @DisplayName("nonsense is refused rather than silently becoming a minute")
        void refusesNonsense() {
            assertThat(Durations.parse("soon")).isEmpty();
            assertThat(Durations.parse("")).isEmpty();
            assertThat(Durations.parse(null)).isEmpty();
            assertThat(Durations.parse("-5d")).isEmpty();
        }

        @Test
        @DisplayName("a duration reads back the way somebody would say it")
        void writesThemOut() {
            assertThat(Durations.describe(Duration.ofMinutes(90))).isEqualTo("1 hour, 30 minutes");
            assertThat(Durations.describe(Duration.ofDays(1))).isEqualTo("1 day");
            assertThat(Durations.describe(Duration.ofSeconds(45))).isEqualTo("45 seconds");
            assertThat(Durations.describe(null)).isEqualTo("for ever");
        }
    }

    @Test
    @DisplayName("a ban or a lift asks to be written at once, not two minutes later")
    void writtenSoon() {
        java.util.List<Runnable> asked = new java.util.ArrayList<>();
        punishments.writeSoon(asked::add);
        UUID target = UUID.randomUUID();

        punishments.punish(target, PunishmentKind.BAN, null, "griefing", null);
        assertThat(asked).as("a crash inside the saving timer's two minutes let a banned player back in")
                .isNotEmpty();
        asked.forEach(Runnable::run);
        assertThat(punishments.waitingToBeWritten()).isZero();

        asked.clear();
        punishments.lift(target, PunishmentKind.BAN, null, "appeal");
        assertThat(asked).isNotEmpty();
    }
}
