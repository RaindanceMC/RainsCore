package de.raindancer.core.moderation.punishment;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** A mute given on survival holds in the arena too, for as long as it has left, and ends there when it ends here. */
class PunishmentTransferTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("a mute arrives with the time it has left, a lifted mute is lifted, a ban is reported")
    void mirror() {
        AtomicLong clock = new AtomicLong(1_000_000);
        UUID ada = UUID.randomUUID();
        UUID mod = UUID.randomUUID();
        Punishments survival = new Punishments(Database.open(directory.resolve("a.db"), CoreSchema.CORE, () -> false), clock::get);
        Punishments arena = new Punishments(Database.open(directory.resolve("b.db"), CoreSchema.CORE, () -> false), clock::get);
        survival.punish(ada, PunishmentKind.MUTE, mod, "spam", Duration.ofMinutes(10));
        clock.addAndGet(Duration.ofMinutes(4).toMillis());

        arena.adopt(ada, survival.export(ada));
        assertThat(arena.isActive(ada, PunishmentKind.MUTE)).isTrue();
        assertThat(arena.remaining(ada, PunishmentKind.MUTE).orElseThrow()).isBetween(Duration.ofMinutes(5), Duration.ofMinutes(6));
        assertThat(arena.active(ada, PunishmentKind.MUTE).orElseThrow().reason()).startsWith("spam");

        survival.lift(ada, PunishmentKind.MUTE, mod, "sorry");
        arena.adopt(ada, survival.export(ada));
        assertThat(arena.isActive(ada, PunishmentKind.MUTE)).as("lifted on survival, lifted here").isFalse();

        survival.punish(ada, PunishmentKind.BAN, mod, "griefing", null);
        assertThat(Punishments.banned(survival.export(ada))).isTrue();
        assertThat(Punishments.banned(arena.export(ada))).isFalse();
    }

    @Test
    @DisplayName("a mute given in the arena itself is never lifted or shortened by survival's state")
    void ownMutesStay() {
        AtomicLong clock = new AtomicLong(1_000_000);
        UUID ada = UUID.randomUUID();
        UUID mod = UUID.randomUUID();
        Punishments survival = new Punishments(Database.open(directory.resolve("c.db"), CoreSchema.CORE, () -> false), clock::get);
        Punishments arena = new Punishments(Database.open(directory.resolve("d.db"), CoreSchema.CORE, () -> false), clock::get);
        arena.punish(ada, PunishmentKind.MUTE, mod, "arena spam", Duration.ofHours(2));

        arena.adopt(ada, survival.export(ada));
        assertThat(arena.isActive(ada, PunishmentKind.MUTE)).as("survival has none, the arena's own stays").isTrue();

        survival.punish(ada, PunishmentKind.MUTE, mod, "short", Duration.ofMinutes(5));
        arena.adopt(ada, survival.export(ada));
        assertThat(arena.remaining(ada, PunishmentKind.MUTE).orElseThrow()).as("not shortened")
                .isGreaterThan(Duration.ofHours(1));
        assertThat(arena.active(ada, PunishmentKind.MUTE).orElseThrow().reason()).isEqualTo("arena spam");

        survival.punish(ada, PunishmentKind.MUTE, mod, "long", Duration.ofDays(1));
        arena.adopt(ada, survival.export(ada));
        assertThat(arena.remaining(ada, PunishmentKind.MUTE).orElseThrow()).as("a longer one from survival holds")
                .isGreaterThan(Duration.ofHours(20));
    }
}
