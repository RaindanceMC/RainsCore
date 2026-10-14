package de.raindancer.core.social.presence;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class PlaytimeTest {

    @TempDir
    Path folder;

    private final UUID alice = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();

    private Playtime playtime() {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        Playtime playtime = new Playtime(database);
        playtime.load();
        return playtime;
    }

    private Playtime server(String id) {
        Playtime playtime = playtime();
        playtime.connect(hub.bus(id));
        return playtime;
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("every minute counts towards playtime; only the minutes not away count as active")
    void counting() {
        Playtime playtime = playtime();
        playtime.minute(alice, false);
        playtime.minute(alice, false);
        playtime.minute(alice, true);

        assertThat(playtime.minutes(alice)).isEqualTo(3);
        assertThat(playtime.activeMinutes(alice)).isEqualTo(2);
        assertThat(playtime.played(alice)).isEqualTo(Duration.ofMinutes(3));
        assertThat(playtime.awayMinutes(alice)).isEqualTo(1);
        assertThat(playtime.playedAway(alice)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("somebody first counted starts from what the game already recorded, once")
    void seeded() {
        Playtime playtime = playtime();
        playtime.seed(alice, 20L * 60 * 90);   // 90 minutes in ticks
        playtime.seed(alice, 20L * 60 * 500);  // ignored: already known
        playtime.minute(alice, false);

        assertThat(playtime.minutes(alice)).isEqualTo(91);
        assertThat(playtime.activeMinutes(alice)).isEqualTo(91);
    }

    @Test
    @DisplayName("it survives a restart; nobody counted yet has played nothing")
    void persisted() {
        Playtime playtime = playtime();
        playtime.minute(alice, false);
        playtime.minute(alice, true);
        assertThat(playtime.isDirty()).isTrue();
        assertThat(playtime.save()).isTrue();
        assertThat(playtime.isDirty()).isFalse();

        Playtime again = playtime();
        assertThat(again.minutes(alice)).isEqualTo(2);
        assertThat(again.activeMinutes(alice)).isEqualTo(1);
        assertThat(again.minutes(UUID.randomUUID())).isZero();
        assertThat(again.isKnown(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("two servers counting the same player at once: both servers' minutes add up")
    void twoServersAddUp() {
        Playtime smp = server("smp");
        Playtime arena = server("arena");
        for (int at = 0; at < 5; at++) {
            smp.minute(alice, false);
        }
        for (int at = 0; at < 3; at++) {
            arena.minute(alice, true);
        }
        smp.save();
        arena.save();
        hub.drain();

        assertThat(smp.minutes(alice)).isEqualTo(8);
        assertThat(arena.minutes(alice)).isEqualTo(8);
        assertThat(smp.activeMinutes(alice)).isEqualTo(5);
    }

    @Test
    @DisplayName("seeding is once for the whole network: a second server's game statistics are not added")
    void seededOncePerNetwork() {
        Playtime smp = server("smp");
        Playtime arena = server("arena");
        smp.seed(alice, 20L * 60 * 90);
        smp.save();
        hub.drain();
        arena.seed(alice, 20L * 60 * 10);
        arena.save();
        hub.drain();
        assertThat(arena.minutes(alice)).isEqualTo(90);
        assertThat(smp.minutes(alice)).isEqualTo(90);
    }

    @Test
    @DisplayName("a 1.x playtime.yml is brought in and kept renamed; every server's file adds its minutes")
    void importsTheOldFiles() throws Exception {
        Path smpFile = folder.resolve("smp-playtime.yml");
        Files.writeString(smpFile, "players:\n  " + alice + ":\n    minutes: 100\n    active: 80\n");
        Path arenaFile = folder.resolve("arena-playtime.yml");
        Files.writeString(arenaFile, "players:\n  " + alice + ":\n    minutes: 20\n    active: 20\n");
        Playtime playtime = playtime();

        playtime.importFrom(smpFile);
        playtime.importFrom(arenaFile);
        playtime.importFrom(arenaFile); // gone now: nothing happens twice

        assertThat(playtime.minutes(alice)).isEqualTo(120);
        assertThat(playtime.activeMinutes(alice)).isEqualTo(100);
        assertThat(smpFile).doesNotExist();
        assertThat(smpFile.resolveSibling("smp-playtime.yml.imported-2.0.0")).exists();
        assertThat(playtime().minutes(alice)).isEqualTo(120);
    }

    @Test
    @DisplayName("a save that fails keeps the minutes for the next one")
    void failedSaveKeeps() throws Exception {
        Database broken = TestDatabases.unusable(folder.resolve("broken.db"), CoreSchema.CORE);
        Playtime playtime = new Playtime(broken);
        playtime.minute(alice, false);
        assertThat(playtime.save()).isFalse();
        assertThat(playtime.isDirty()).isTrue();
        assertThat(playtime.minutes(alice)).isEqualTo(1);
        broken.close();
    }
}
