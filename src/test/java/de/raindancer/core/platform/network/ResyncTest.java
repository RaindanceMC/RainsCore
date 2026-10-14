package de.raindancer.core.platform.network;

import de.raindancer.core.content.achievement.Achievement;
import de.raindancer.core.content.achievement.Achievements;
import de.raindancer.core.data.runs.Run;
import de.raindancer.core.data.runs.RunHistory;
import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.moderation.punishment.Punishments;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.core.world.poi.Poi;
import de.raindancer.core.world.poi.PoiStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every store, after a resync, holds what the database holds — including what was written by a server
 * whose announcement it never heard — and still holds what it has not written itself.
 */
@Tag("database")
class ResyncTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BEA = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @TempDir
    Path folder;

    private final List<Database> databases = new ArrayList<>();

    private Database database() {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        return database;
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("punishments: a ban nobody announced arrives; an unwritten mute here stays")
    void punishments() {
        Punishments silent = new Punishments(database(), System::currentTimeMillis);
        Punishments here = new Punishments(database(), System::currentTimeMillis);
        here.load();
        silent.punish(ALEX, PunishmentKind.BAN, null, "x", null);
        silent.flush();
        here.punish(BEA, PunishmentKind.MUTE, null, "unwritten", null);

        here.refreshAll();

        assertThat(here.isActive(ALEX, PunishmentKind.BAN)).isTrue();
        assertThat(here.isActive(BEA, PunishmentKind.MUTE)).isTrue();
    }

    @Test
    @DisplayName("punishments: a ban lifted where nobody heard it is lifted here too")
    void punishmentLifted() {
        Punishments silent = new Punishments(database(), System::currentTimeMillis);
        Punishments here = new Punishments(database(), System::currentTimeMillis);
        silent.punish(ALEX, PunishmentKind.BAN, null, "x", null);
        silent.flush();
        here.load();
        silent.load();
        silent.lift(ALEX, PunishmentKind.BAN, null, "appeal");
        silent.flush();
        here.refreshAll();
        assertThat(here.isActive(ALEX, PunishmentKind.BAN)).isFalse();
    }

    @Test
    @DisplayName("identities and nicknames")
    void identities() {
        Identities silent = new Identities(database());
        Identities here = new Identities(database());
        here.load();
        silent.setPrefix(ALEX, "<gold>[A] ");
        silent.flush();
        here.setPrefix(BEA, "<red>[B] ");
        here.refreshAll();
        assertThat(here.prefix(ALEX)).contains("<gold>[A] ");
        assertThat(here.prefix(BEA)).contains("<red>[B] ");

        Nicknames silentNames = new Nicknames(database());
        Nicknames hereNames = new Nicknames(database());
        hereNames.load();
        silentNames.remember(ALEX, "Lexi");
        silentNames.flush();
        hereNames.refreshAll();
        assertThat(hereNames.ownerOf("lexi")).contains(ALEX);
    }

    @Test
    @DisplayName("places: added and deleted where nobody heard")
    void places() {
        PoiStore silent = new PoiStore(database());
        PoiStore here = new PoiStore(database());
        Poi gone = Poi.builder("old", "world", 0, 0, 0).build();
        silent.save(gone);
        silent.flush();
        here.load();
        silent.load();
        Poi added = Poi.builder("new", "world", 1, 1, 1).build();
        silent.save(added);
        silent.delete(gone.id());
        silent.flush();
        Poi mine = Poi.builder("mine", "world", 2, 2, 2).build();
        here.save(mine);

        here.refreshAll();

        assertThat(here.byId(added.id())).isPresent();
        assertThat(here.byId(gone.id())).isEmpty();
        assertThat(here.byId(mine.id())).isPresent();
    }

    @Test
    @DisplayName("achievements: progress from elsewhere plus progress here")
    void achievements() {
        Achievements silent = new Achievements(folder.resolve("a.yml"), database(), System::currentTimeMillis);
        Achievements here = new Achievements(folder.resolve("b.yml"), database(), System::currentTimeMillis);
        for (Achievements each : List.of(silent, here)) {
            each.load();
            each.define(Achievement.builder("mining", "miner").title("Miner").goal(100).build());
        }
        silent.progress(ALEX, "mining:miner", 20);
        silent.flush();
        here.progress(ALEX, "mining:miner", 3);
        here.refreshAll();
        assertThat(here.progressOf(ALEX, "mining:miner")).isEqualTo(23);
    }

    @Test
    @DisplayName("runs of a game")
    void runs() {
        RunHistory silent = new RunHistory(database(), "speedrun");
        RunHistory here = new RunHistory(database(), "speedrun");
        silent.load();
        here.load();
        Run run = new Run("r1", "any%", Instant.ofEpochMilli(1_000), 60_000, true, true,
                Map.of(ALEX, "Alex"), Map.of(), Map.of());
        silent.add(run);
        silent.flush();
        here.refreshAll();
        assertThat(here.byId("r1")).isPresent();
    }
}
