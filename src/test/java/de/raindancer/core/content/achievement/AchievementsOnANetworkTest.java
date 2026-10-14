package de.raindancer.core.content.achievement;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class AchievementsOnANetworkTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String MINER = "mining:miner";
    private static final String FIRST = "mining:first-block";

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();

    private Achievements server(String id) {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        Achievements store = new Achievements(folder.resolve(id + "-achievements.yml"), database, clock::get);
        store.load();
        store.define(Achievement.builder("mining", "miner").title("Miner").goal(100).build());
        store.define(Achievement.builder("mining", "first-block").title("First block").build());
        store.connect(hub.bus(id));
        return store;
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("what is earned on one server is earned on the other, not announced there again")
    void earnedTravels() {
        Achievements smp = server("smp");
        Achievements arena = server("arena");
        List<String> announcedInArena = new ArrayList<>();
        arena.onEarned((player, achievement) -> announcedInArena.add(achievement.key()));

        assertThat(smp.award(ALEX, FIRST)).isTrue();
        smp.flush();
        hub.drain();

        assertThat(arena.hasEarned(ALEX, FIRST)).isTrue();
        assertThat(announcedInArena).isEmpty();
        assertThat(arena.award(ALEX, FIRST)).as("already theirs").isFalse();
    }

    @Test
    @DisplayName("progress made on two servers at once is added up, none of it lost")
    void progressFromTwoServersAddsUp() {
        Achievements smp = server("smp");
        Achievements arena = server("arena");
        // Around a server switch both servers can hold unwritten progress for the same player.
        smp.progress(ALEX, MINER, 30);
        arena.progress(ALEX, MINER, 12);
        smp.flush();
        arena.flush();
        hub.drain();

        assertThat(smp.progressOf(ALEX, MINER)).isEqualTo(42);
        assertThat(arena.progressOf(ALEX, MINER)).isEqualTo(42);
    }

    @Test
    @DisplayName("a refresh keeps this server's unwritten progress on top of what was written elsewhere")
    void unwrittenProgressSurvivesARefresh() {
        Achievements smp = server("smp");
        Achievements arena = server("arena");
        smp.progress(ALEX, MINER, 10);
        smp.flush();
        arena.progress(ALEX, MINER, 5);
        hub.drain();
        arena.refresh(ALEX);
        assertThat(arena.progressOf(ALEX, MINER)).isEqualTo(15);
        arena.flush();
        hub.drain();
        assertThat(smp.progressOf(ALEX, MINER)).isEqualTo(15);
    }

    @Test
    @DisplayName("setting progress outright wins over what was there, then adding goes on from it")
    void setThenAdd() {
        Achievements smp = server("smp");
        Achievements arena = server("arena");
        smp.progress(ALEX, MINER, 50);
        smp.flush();
        hub.drain();
        arena.setProgress(ALEX, MINER, 7);
        arena.progress(ALEX, MINER, 3);
        arena.flush();
        hub.drain();
        assertThat(smp.progressOf(ALEX, MINER)).isEqualTo(10);
    }

    @Test
    @DisplayName("a revoke on one server takes it away on the other")
    void revokeTravels() {
        Achievements smp = server("smp");
        Achievements arena = server("arena");
        smp.award(ALEX, FIRST);
        smp.flush();
        hub.drain();
        assertThat(arena.revoke(ALEX, FIRST)).isTrue();
        arena.flush();
        hub.drain();
        assertThat(smp.hasEarned(ALEX, FIRST)).isFalse();
    }

    @Test
    @DisplayName("the time it was first earned is kept when another server earns it again a moment later")
    void firstEarnedTimeKept() {
        Achievements smp = server("smp");
        Achievements arena = server("arena");
        smp.award(ALEX, FIRST);
        var first = smp.earnedAt(ALEX, FIRST).orElseThrow();
        clock.addAndGet(5_000);
        arena.award(ALEX, FIRST);
        smp.flush();
        arena.flush();
        hub.drain();
        assertThat(arena.earnedAt(ALEX, FIRST)).contains(first);
        assertThat(smp.earnedAt(ALEX, FIRST)).contains(first);
    }

    @Test
    @DisplayName("a write that fails keeps its operations, in front of the ones that came after")
    void failedWriteKeepsOrder() throws Exception {
        Database broken = TestDatabases.unusable(folder.resolve("broken.db"), CoreSchema.CORE);
        Achievements store = new Achievements(folder.resolve("b.yml"), broken, clock::get);
        store.define(Achievement.builder("mining", "miner").title("Miner").goal(100).build());
        store.progress(ALEX, MINER, 4);
        store.flush();
        assertThat(store.isDirty()).isTrue();
        assertThat(store.progressOf(ALEX, MINER)).isEqualTo(4);
        broken.close();
    }
}
