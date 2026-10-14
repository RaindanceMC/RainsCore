package de.raindancer.core.moderation.punishment;

import de.raindancer.core.testkit.TestDatabases;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Whether a punishment actually stops somebody, and what they are told.
 *
 * <h2>Why this is a class of its own</h2>
 * Because until now it did not exist, and that was the real gap: {@link Punishments} recorded a ban
 * perfectly — history, expiry, who lifted it — and nothing anywhere stopped a banned player joining.
 * A ban that does not ban is worse than no ban, because everybody believes it worked.
 *
 * <p>The decision and the listener are separate for the usual reason. "May this player join, and if
 * not what do they see" is a pure question about a punishment and a clock; the Bukkit event handler
 * that asks it is three lines and needs a server. Everything worth getting right is on this side.
 */
@org.junit.jupiter.api.Tag("database")
class PunishmentGuardTest {

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
    private PunishmentGuard guard;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000_000L);
        database = openDatabase();
        punishments = new Punishments(database, clock::get);
        guard = new PunishmentGuard(punishments, clock::get);
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    // ------------------------------------------------------------------ joining

    @Nested
    @DisplayName("joining")
    class Joining {

        @Test
        @DisplayName("somebody with a clean record gets in")
        void cleanRecordJoins() {
            assertThat(guard.mayJoin(ALICE)).isTrue();
            assertThat(guard.joinRefusal(ALICE)).isEmpty();
        }

        /** The gap this class was written to close. */
        @Test
        @DisplayName("a banned player does not")
        void bannedPlayerIsRefused() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "griefing", Duration.ofDays(7));
            assertThat(guard.mayJoin(ALICE))
                    .as("a ban that does not ban is worse than no ban")
                    .isFalse();
        }

        @Test
        @DisplayName("a muted player still gets in — that is the difference between the two")
        void mutedPlayerJoins() {
            punishments.punish(ALICE, PunishmentKind.MUTE, MOD, "spam", Duration.ofHours(1));
            assertThat(guard.mayJoin(ALICE)).isTrue();
        }

        @Test
        @DisplayName("a ban that has expired lets them back in without anybody lifting it")
        void expiredBanLetsThemIn() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "a week", Duration.ofDays(7));
            clock.addAndGet(Duration.ofDays(8).toMillis());
            assertThat(guard.mayJoin(ALICE)).isTrue();
        }

        @Test
        @DisplayName("a lifted ban lets them in at once")
        void liftedBanLetsThemIn() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "griefing", null);
            punishments.lift(ALICE, PunishmentKind.BAN, MOD, "appealed");
            assertThat(guard.mayJoin(ALICE)).isTrue();
        }
    }

    // ------------------------------------------------------------------ what they are told

    @Nested
    @DisplayName("the screen a banned player sees")
    class TheBanScreen {

        @Test
        @DisplayName("says why, and who by")
        void saysWhyAndWho() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "griefing spawn",
                    Duration.ofDays(7));
            String screen = plain(guard.joinRefusal(ALICE).orElseThrow());

            assertThat(screen)
                    .as("a player who is not told why cannot appeal, and asks in Discord instead")
                    .contains("griefing spawn");
        }

        @Test
        @DisplayName("says how long is left, not when it started")
        void saysHowLongIsLeft() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "griefing", Duration.ofDays(7));
            clock.addAndGet(Duration.ofDays(5).toMillis());

            assertThat(plain(guard.joinRefusal(ALICE).orElseThrow()))
                    .as("'2 days' is useful; a timestamp in the server's timezone is not")
                    .contains("2 days");
        }

        @Test
        @DisplayName("a permanent ban says it does not expire rather than showing a huge number")
        void permanentSaysSo() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "never coming back", null);
            String screen = plain(guard.joinRefusal(ALICE).orElseThrow());
            assertThat(screen)
                    .as("what a player needs to know is that waiting will not help")
                    .containsIgnoringCase("does not expire");
            assertThat(screen)
                    .as("and not a duration, which is what a very large number would read as")
                    .doesNotContain("Time left");
        }

        @Test
        @DisplayName("the appeal line is shown when the server has set one")
        void showsTheAppealLine() {
            guard.appealMessage("appeal at example.invalid/appeal");
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "griefing", null);
            assertThat(plain(guard.joinRefusal(ALICE).orElseThrow()))
                    .contains("example.invalid/appeal");
        }

        @Test
        @DisplayName("and left out entirely when it has not")
        void hidesAnEmptyAppealLine() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "griefing", null);
            assertThat(plain(guard.joinRefusal(ALICE).orElseThrow()))
                    .doesNotContain("appeal");
        }
    }

    // ------------------------------------------------------------------ speaking

    @Nested
    @DisplayName("speaking")
    class Speaking {

        @Test
        @DisplayName("a muted player is stopped, and told how long for")
        void mutedIsStopped() {
            punishments.punish(ALICE, PunishmentKind.MUTE, MOD, "spam", Duration.ofMinutes(30));
            assertThat(guard.maySpeak(ALICE)).isFalse();
            assertThat(plain(guard.speakRefusal(ALICE).orElseThrow()))
                    .contains("30 minutes")
                    .contains("spam");
        }

        @Test
        @DisplayName("everybody else speaks")
        void othersSpeak() {
            assertThat(guard.maySpeak(BOB)).isTrue();
            assertThat(guard.speakRefusal(BOB)).isEmpty();
        }

        @Test
        @DisplayName("a banned player is not separately muted — they are not here to talk")
        void bannedIsNotMuted() {
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "griefing", null);
            assertThat(guard.maySpeak(ALICE)).isTrue();
        }

        @Test
        @DisplayName("a mute that has expired lets them talk")
        void expiredMuteLetsThemTalk() {
            punishments.punish(ALICE, PunishmentKind.MUTE, MOD, "spam", Duration.ofMinutes(10));
            clock.addAndGet(Duration.ofMinutes(11).toMillis());
            assertThat(guard.maySpeak(ALICE)).isTrue();
        }
    }

    // ------------------------------------------------------------------ building

    @Nested
    @DisplayName("building and breaking")
    class Building {

        @Test
        @DisplayName("a frozen player cannot, and is told why")
        void frozenCannotBuild() {
            punishments.punish(ALICE, PunishmentKind.FREEZE, MOD, "under investigation", null);
            assertThat(guard.mayBuild(ALICE)).isFalse();
            assertThat(plain(guard.buildRefusal(ALICE).orElseThrow()))
                    .contains("under investigation");
        }

        @Test
        @DisplayName("a muted player can build — the two are not the same thing")
        void mutedCanBuild() {
            punishments.punish(ALICE, PunishmentKind.MUTE, MOD, "spam", null);
            assertThat(guard.mayBuild(ALICE)).isTrue();
        }

        @Test
        @DisplayName("everybody else can")
        void othersCanBuild() {
            assertThat(guard.mayBuild(BOB)).isTrue();
        }
    }

    // ------------------------------------------------------------------ switching it off

    @Nested
    @DisplayName("when enforcement is switched off")
    class Disabled {

        @Test
        @DisplayName("nothing is stopped, but everything is still recorded")
        void recordsWithoutEnforcing() {
            guard.enabled(false);
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "griefing", null);

            assertThat(guard.mayJoin(ALICE))
                    .as("a server driving punishments from somewhere else keeps its own behaviour")
                    .isTrue();
            assertThat(punishments.isActive(ALICE, PunishmentKind.BAN))
                    .as("the record is still the record")
                    .isTrue();
        }

        @Test
        @DisplayName("each kind can be switched off on its own")
        void perKind() {
            guard.enforce(PunishmentKind.MUTE, false);
            punishments.punish(ALICE, PunishmentKind.MUTE, MOD, "spam", null);
            punishments.punish(ALICE, PunishmentKind.BAN, MOD, "griefing", null);

            assertThat(guard.maySpeak(ALICE)).isTrue();
            assertThat(guard.mayJoin(ALICE)).isFalse();
        }
    }

    // ------------------------------------------------------------------ misuse

    @Test
    @DisplayName("nulls do not throw")
    void survivesNulls() {
        assertThatCode(() -> {
            guard.mayJoin(null);
            guard.maySpeak(null);
            guard.mayBuild(null);
            guard.joinRefusal(null);
            guard.enforce(null, false);
        }).doesNotThrowAnyException();
        assertThat(guard.mayJoin(null))
                .as("an unknown player is not a punished one")
                .isTrue();
    }
}
