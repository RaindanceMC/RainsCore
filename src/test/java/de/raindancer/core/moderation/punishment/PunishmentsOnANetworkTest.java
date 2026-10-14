package de.raindancer.core.moderation.punishment;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Two servers of one network: one database, one bus, a store each. */
@Tag("database")
class PunishmentsOnANetworkTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID MOD = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();
    private Punishments smp;
    private Punishments arena;

    private Punishments server(String id) {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        Punishments store = new Punishments(database, clock::get);
        store.load();
        store.connect(hub.bus(id));
        return store;
    }

    @BeforeEach
    void start() {
        smp = server("smp");
        arena = server("arena");
    }

    @AfterEach
    void stop() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("a ban given on one server is a ban on the other once it is written")
    void banTravels() {
        smp.punish(STEVE, PunishmentKind.BAN, MOD, "griefing", null);
        assertThat(arena.isActive(STEVE, PunishmentKind.BAN)).isFalse();

        smp.flush();
        hub.drain();

        assertThat(arena.isActive(STEVE, PunishmentKind.BAN)).isTrue();
        assertThat(arena.active(STEVE, PunishmentKind.BAN)).get()
                .extracting(Punishment::reason).isEqualTo("griefing");
    }

    @Test
    @DisplayName("a lift on the other server reaches back")
    void liftTravels() {
        smp.punish(STEVE, PunishmentKind.MUTE, MOD, "spam", Duration.ofHours(1));
        smp.flush();
        hub.drain();
        assertThat(arena.isActive(STEVE, PunishmentKind.MUTE)).isTrue();

        assertThat(arena.lift(STEVE, PunishmentKind.MUTE, MOD, "served")).isTrue();
        arena.flush();
        hub.drain();

        assertThat(smp.isActive(STEVE, PunishmentKind.MUTE)).isFalse();
        assertThat(smp.history(STEVE)).singleElement()
                .extracting(Punishment::lifterReason).isEqualTo("served");
    }

    @Test
    @DisplayName("hearing about a player never drops what this server has not written yet")
    void ownUnwrittenChangeSurvivesARefresh() {
        smp.punish(STEVE, PunishmentKind.MUTE, MOD, "from smp", Duration.ofHours(1));
        smp.flush();
        arena.punish(STEVE, PunishmentKind.FREEZE, MOD, "from arena, not written yet", null);

        hub.drain();
        arena.refresh(STEVE);

        assertThat(arena.isActive(STEVE, PunishmentKind.MUTE)).isTrue();
        assertThat(arena.isActive(STEVE, PunishmentKind.FREEZE)).isTrue();
        arena.flush();
        hub.drain();
        assertThat(smp.isActive(STEVE, PunishmentKind.FREEZE)).isTrue();
    }

    @Test
    @DisplayName("the login check reads the database itself, so a ban arrives even when no event did")
    void loginReadsTheDatabase() {
        Punishments deaf = new Punishments(databases.getFirst(), clock::get);
        deaf.load();
        smp.punish(STEVE, PunishmentKind.BAN, MOD, "x", null);
        smp.flush();

        assertThat(deaf.isActive(STEVE, PunishmentKind.BAN)).isFalse();
        deaf.refresh(STEVE);
        assertThat(deaf.isActive(STEVE, PunishmentKind.BAN)).isTrue();
    }

    @Test
    @DisplayName("refreshing somebody nobody has punished is harmless")
    void refreshNobody() {
        UUID nobody = UUID.randomUUID();
        arena.refresh(nobody);
        assertThat(arena.history(nobody)).isEmpty();
        arena.refresh(null);
    }

    @Test
    @DisplayName("while the database is gone a refresh keeps what is known rather than forgetting every ban")
    void refreshOnABrokenDatabaseKeepsTheCache() throws Exception {
        Database broken = TestDatabases.unusable(folder.resolve("broken.db"), CoreSchema.CORE);
        Punishments store = new Punishments(broken, clock::get);
        store.punish(STEVE, PunishmentKind.BAN, MOD, "kept in memory", null);
        store.refresh(STEVE);
        assertThat(store.isActive(STEVE, PunishmentKind.BAN)).isTrue();
        broken.close();
    }

    @Test
    @DisplayName("a check running while the player is refreshed never sees them unpunished")
    void noGapDuringRefresh() throws Exception {
        smp.punish(STEVE, PunishmentKind.BAN, MOD, "x", null);
        smp.flush();
        arena.refresh(STEVE);
        ExecutorService threads = Executors.newFixedThreadPool(4);
        List<Future<Boolean>> checks = new ArrayList<>();
        for (int at = 0; at < 200; at++) {
            threads.submit(() -> arena.refresh(STEVE));
            checks.add(threads.submit(() -> arena.isActive(STEVE, PunishmentKind.BAN)));
        }
        for (Future<Boolean> check : checks) {
            assertThat(check.get(30, TimeUnit.SECONDS)).isTrue();
        }
        threads.shutdownNow();
    }

    @Test
    @DisplayName("a joining player is checked against the database: a ban from another server stops them at the door")
    void joinIsCheckedAgainstTheDatabase() {
        Punishments deaf = new Punishments(databases.getFirst(), clock::get);
        deaf.load();
        PunishmentGuard guard = new PunishmentGuard(deaf, clock::get);
        smp.punish(STEVE, PunishmentKind.BAN, MOD, "x", null);
        smp.flush();

        assertThat(guard.mayJoin(STEVE)).as("this server's copy has not heard").isTrue();
        guard.readFromDatabase(STEVE);
        assertThat(guard.mayJoin(STEVE)).isFalse();
    }
}
