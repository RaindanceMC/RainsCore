package de.raindancer.core.moderation.punishment;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.moderation.audit.AuditSearch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.Tag("database")
class BlockedAttemptsTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    private final UUID mushy = UUID.fromString("e392eece-9274-4560-8672-32185a9b2f55");
    private final UUID bo = UUID.randomUUID();
    private Database database;
    private Audit audit;
    private BlockedAttempts attempts;

    private final Punishment ban = new Punishment("b1", mushy, PunishmentKind.BAN, null, "griefing",
            Instant.EPOCH, null, null, null, null);
    private final Punishment mute = new Punishment("m1", bo, PunishmentKind.MUTE, null, "spam",
            Instant.EPOCH, null, null, null, null);

    @BeforeEach
    void open() {
        database = TestDatabases.open(folder.resolve("audit.db"), CoreSchema.AUDIT, () -> false);
        audit = new Audit(database, clock::get);
        attempts = new BlockedAttempts(audit, clock::get);
    }

    @AfterEach
    void close() {
        BlockedAttempts.clearWatchers();
        database.close();
    }

    @Test
    @DisplayName("every attempt goes into the audit journal as moderation/blocked-<kind>, with why it was blocked")
    void intoTheAudit() {
        attempts.record(mushy, "MushyTheFish", BlockedAttempts.Kind.JOIN, ban, "", "a1b2c3");
        audit.flush();
        List<AuditEntry> entries = audit.search(AuditSearch.in("moderation").withAction("blocked-join"));
        assertThat(entries).hasSize(1);
        AuditEntry entry = entries.getFirst();
        assertThat(entry.subject()).isEqualTo(mushy);
        assertThat(entry.subjectName()).isEqualTo("MushyTheFish");
        assertThat(entry.fields()).containsEntry("punishment", "BAN").containsEntry("reason", "griefing")
                .containsEntry("address", "a1b2c3");
    }

    @Test
    @DisplayName("they are read back by player, kind and time range, newest first; to is exclusive")
    void search() {
        attempts.record(mushy, "MushyTheFish", BlockedAttempts.Kind.JOIN, ban, "", null);
        clock.addAndGet(1000);
        attempts.record(bo, "Bo", BlockedAttempts.Kind.CHAT, mute, "hello?", null);
        clock.addAndGet(1000);
        attempts.record(mushy, "MushyTheFish", BlockedAttempts.Kind.JOIN, ban, "", null);
        long start = 1_800_000_000_000L;

        assertThat(attempts.search(Set.of(), null, null, Set.of(), 50))
                .extracting(BlockedAttempts.Attempt::kind)
                .containsExactly(BlockedAttempts.Kind.JOIN, BlockedAttempts.Kind.CHAT, BlockedAttempts.Kind.JOIN);
        assertThat(attempts.search(Set.of(mushy), null, null, Set.of(), 50)).hasSize(2)
                .allSatisfy(attempt -> assertThat(attempt.punishment()).isEqualTo(PunishmentKind.BAN));
        List<BlockedAttempts.Attempt> chat = attempts.search(Set.of(), null, null,
                EnumSet.of(BlockedAttempts.Kind.CHAT), 50);
        assertThat(chat).singleElement().satisfies(attempt -> {
            assertThat(attempt.detail()).isEqualTo("hello?");
            assertThat(attempt.name()).isEqualTo("Bo");
            assertThat(attempt.reason()).isEqualTo("spam");
        });
        assertThat(attempts.search(Set.of(), Instant.ofEpochMilli(start + 1000), Instant.ofEpochMilli(start + 2000),
                Set.of(), 50)).extracting(BlockedAttempts.Attempt::player).containsExactly(bo);
        assertThat(attempts.search(Set.of(), null, null, Set.of(), 1)).hasSize(1);
        assertThat(attempts.count(Set.of(mushy))).isEqualTo(2);
    }

    @Test
    @DisplayName("building while frozen is kept once per half minute, so holding a button does not flood the journal")
    void buildIsThrottled() {
        Punishment freeze = new Punishment("f1", bo, PunishmentKind.FREEZE, null, "x", Instant.EPOCH, null,
                null, null, null);
        for (int i = 0; i < 20; i++) {
            attempts.record(bo, "Bo", BlockedAttempts.Kind.BUILD, freeze, "placed STONE", null);
            clock.addAndGet(200);
        }
        clock.addAndGet(BlockedAttempts.BUILD_QUIET.toMillis());
        attempts.record(bo, "Bo", BlockedAttempts.Kind.BUILD, freeze, "placed STONE", null);
        assertThat(attempts.search(Set.of(bo), null, null, Set.of(), 50)).hasSize(2);
    }

    @Test
    @DisplayName("watchers hear every attempt as it happens; one that throws does not stop the rest")
    void watchers() {
        List<String> heard = new ArrayList<>();
        Consumer<BlockedAttempts.Attempt> broken = attempt -> {
            throw new IllegalStateException("broken watcher");
        };
        Consumer<BlockedAttempts.Attempt> listening = attempt -> heard.add(attempt.name() + " " + attempt.kind());
        BlockedAttempts.watch(broken);
        BlockedAttempts.watch(listening);
        attempts.record(bo, "Bo", BlockedAttempts.Kind.CHAT, mute, "hi", null);
        BlockedAttempts.unwatch(listening);
        attempts.record(bo, "Bo", BlockedAttempts.Kind.CHAT, mute, "hi again", null);
        assertThat(heard).containsExactly("Bo CHAT");
    }

    @Test
    @DisplayName("an address is kept only as a keyed hash: the same address and key match, nothing else does")
    void addressHash() {
        byte[] key = new byte[32];
        byte[] other = new byte[32];
        other[0] = 1;
        String hashed = BlockedAttempts.hashAddress(key, "203.0.113.7");
        assertThat(hashed).hasSize(16).doesNotContain("203");
        assertThat(BlockedAttempts.hashAddress(key, "203.0.113.7")).isEqualTo(hashed);
        assertThat(BlockedAttempts.hashAddress(key, "203.0.113.8")).isNotEqualTo(hashed);
        assertThat(BlockedAttempts.hashAddress(other, "203.0.113.7")).isNotEqualTo(hashed);
        assertThat(BlockedAttempts.hashAddress(key, null)).isNull();
    }

    @Test
    @DisplayName("nothing without a player is recorded")
    void nobody() {
        attempts.record(null, "x", BlockedAttempts.Kind.JOIN, ban, "", null);
        assertThat(attempts.search(Set.of(), null, null, Set.of(), 50)).isEmpty();
    }
}
