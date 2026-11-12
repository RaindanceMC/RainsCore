package de.raindancer.core.moderation.feed;

import de.raindancer.core.moderation.audit.AuditEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSessionRecorderTest {

    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();

    private final AtomicLong now = new AtomicLong(1_000);
    private final AdminSessionRecorder recorder = new AdminSessionRecorder(now::get);

    @Test
    @DisplayName("every command of the stay comes back in order, each with when it was run")
    void commandsInOrder() {
        recorder.begin(STAFF);
        now.set(2_000);
        recorder.command(STAFF, "/gamemode creative");
        now.set(3_000);
        recorder.command(STAFF, "/give Alex diamond 3");
        now.set(9_000);

        AdminSessionRecap recap = recorder.end(STAFF).orElseThrow();

        assertThat(recap.commands()).containsExactly(
                new AdminSessionRecap.Line(2_000, "/gamemode creative"),
                new AdminSessionRecap.Line(3_000, "/give Alex diamond 3"));
        assertThat(recap.startedAt()).isEqualTo(1_000);
        assertThat(recap.endedAt()).isEqualTo(9_000);
    }

    @Test
    @DisplayName("nothing is written down for somebody not in a stay, and a stay ended twice is gone")
    void onlyDuringAStay() {
        recorder.command(STAFF, "/fly");
        recorder.placed(STAFF, "STONE");

        assertThat(recorder.end(STAFF)).isEmpty();
        assertThat(recorder.recording(STAFF)).isFalse();
    }

    @Test
    @DisplayName("the command that ends the stay (/admin, /admin off, an alias) is not part of it, /admin bypass is")
    void leavingCommandDropped() {
        recorder.begin(STAFF);
        recorder.command(STAFF, "/admin bypass");
        recorder.command(STAFF, "/essentials:staffmode off");

        assertThat(recorder.end(STAFF).orElseThrow().commands()).extracting(AdminSessionRecap.Line::text)
                .containsExactly("/admin bypass");
    }

    @Test
    @DisplayName("passwords and tokens typed into a command are hidden")
    void secretsHidden() {
        recorder.begin(STAFF);
        recorder.command(STAFF, "/login hunter2");
        recorder.command(STAFF, "/discordlink hushtoken abc.def");

        assertThat(recorder.end(STAFF).orElseThrow().commands()).extracting(AdminSessionRecap.Line::text)
                .containsExactly("/login ‹hidden›", "/discordlink hushtoken ‹hidden›");
    }

    @Test
    @DisplayName("what the audit log says this staff member did during the stay is kept; others' entries are not")
    void auditedActions() {
        recorder.begin(STAFF);
        recorder.audited(AuditEntry.of("moderation", "banned").by(STAFF, "Rain").to(PLAYER, "Alex")
                .saying("griefing").at(Instant.ofEpochMilli(4_000)));
        recorder.audited(AuditEntry.of("economy", "gave").by(PLAYER, "Alex").saying("5 ₯").at(Instant.ofEpochMilli(5_000)));
        recorder.audited(AuditEntry.of("player", "joined").by(null, null).at(Instant.ofEpochMilli(6_000)));

        assertThat(recorder.end(STAFF).orElseThrow().actions())
                .containsExactly(new AdminSessionRecap.Line(4_000, "banned Alex — griefing"));
    }

    @Test
    @DisplayName("blocks are counted by kind, teleports and worlds and game mode switches are kept")
    void worldChanges() {
        recorder.begin(STAFF);
        recorder.placed(STAFF, "STONE");
        recorder.placed(STAFF, "STONE");
        recorder.broken(STAFF, "OAK_LOG");
        recorder.teleported(STAFF, "world", "world_nether");
        recorder.teleported(STAFF, "world_nether", "world_nether");
        recorder.gameMode(STAFF, "SURVIVAL", "CREATIVE");

        AdminSessionRecap recap = recorder.end(STAFF).orElseThrow();

        assertThat(recap.placed()).containsExactlyEntriesOf(java.util.Map.of("STONE", 2));
        assertThat(recap.broken()).containsExactlyEntriesOf(java.util.Map.of("OAK_LOG", 1));
        assertThat(recap.teleports()).isEqualTo(2);
        assertThat(recap.worlds()).containsExactly("world_nether");
        assertThat(recap.gameModes()).containsExactly("SURVIVAL → CREATIVE");
        assertThat(recap.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("a stay in which nothing happened says so")
    void emptyStay() {
        recorder.begin(STAFF);
        recorder.command(STAFF, "/admin");

        assertThat(recorder.end(STAFF).orElseThrow().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a long stay keeps the first commands up to the limit and counts the rest; long ones are cut")
    void limits() {
        recorder.begin(STAFF);
        for (int i = 0; i < AdminSessionRecorder.MAX_LINES + 7; i++) {
            recorder.command(STAFF, "/tp " + i);
        }
        recorder.command(STAFF, "/say " + "x".repeat(1_000));

        AdminSessionRecap recap = recorder.end(STAFF).orElseThrow();

        assertThat(recap.commands()).hasSize(AdminSessionRecorder.MAX_LINES);
        assertThat(recap.commandsOmitted()).isEqualTo(8);
        assertThat(recap.commands().getFirst().text()).isEqualTo("/tp 0");
    }

    @Test
    @DisplayName("text is cut to the limit")
    void longCommandCut() {
        recorder.begin(STAFF);
        recorder.command(STAFF, "/say " + "x".repeat(1_000));

        assertThat(recorder.end(STAFF).orElseThrow().commands().getFirst().text())
                .hasSize(AdminSessionRecorder.MAX_TEXT).endsWith("…");
    }

    @Test
    @DisplayName("being announced as in again keeps the stay that is running")
    void beginAgainKeeps() {
        recorder.begin(STAFF);
        recorder.command(STAFF, "/fly");
        recorder.begin(STAFF);

        assertThat(recorder.end(STAFF).orElseThrow().commands()).hasSize(1);
    }
}
