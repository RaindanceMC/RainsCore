package de.raindancer.core.ui.identity;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nickname directory: who goes by what, kept across restarts, so a command can be pointed at
 * somebody by the name everybody actually knows them by.
 */
@org.junit.jupiter.api.Tag("database")
class NicknamesTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @TempDir
    Path directory;

    private Database database;

    private Database database() {
        if (database == null || !database.isUsable()) {
            database = TestDatabases.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        }
        return database;
    }

    @AfterEach
    void close() {
        if (database != null) {
            database.close();
        }
    }

    @Nested
    @DisplayName("the typed form")
    class Typed {

        @Test
        @DisplayName("is lower case with every run of spaces one underscore")
        void spacesBecomeUnderscores() {
            assertThat(Nicknames.typed("Lilly  Pad")).isEqualTo("lilly_pad");
            assertThat(Nicknames.typed("  Sir Lancelot the Brave ")).isEqualTo("sir_lancelot_the_brave");
        }

        @Test
        @DisplayName("keeps its case for suggesting, so the player sees the nickname as it was written")
        void suggestionKeepsCase() {
            assertThat(Nicknames.suggestion("Lilly Pad")).isEqualTo("Lilly_Pad");
        }

        @Test
        @DisplayName("nothing typed is nothing")
        void blank() {
            assertThat(Nicknames.typed(null)).isEmpty();
            assertThat(Nicknames.typed("   ")).isEmpty();
        }
    }

    @Test
    @DisplayName("a remembered nickname leads back to its owner, however it is typed")
    void ownerOf() {
        Nicknames nicknames = new Nicknames(database());
        nicknames.remember(ALICE, "Lilly Pad");

        assertThat(nicknames.ownerOf("lilly_pad")).contains(ALICE);
        assertThat(nicknames.ownerOf("LILLY_PAD")).contains(ALICE);
        assertThat(nicknames.ownerOf("Lilly Pad")).contains(ALICE);
        assertThat(nicknames.ownerOf("Lilly")).isEmpty();
        assertThat(nicknames.of(ALICE)).contains("Lilly Pad");
    }

    @Test
    @DisplayName("a new nickname replaces the old one, and the old one leads nowhere any more")
    void replaced() {
        Nicknames nicknames = new Nicknames(database());
        nicknames.remember(ALICE, "Lilly");
        nicknames.remember(ALICE, "Rose");

        assertThat(nicknames.ownerOf("lilly")).isEmpty();
        assertThat(nicknames.ownerOf("rose")).contains(ALICE);
    }

    @Test
    @DisplayName("forgetting, or remembering a blank one, takes the nickname away")
    void forgotten() {
        Nicknames nicknames = new Nicknames(database());
        nicknames.remember(ALICE, "Lilly");
        nicknames.remember(BOB, "Bobby");
        nicknames.clear(ALICE);
        nicknames.remember(BOB, " ");

        assertThat(nicknames.ownerOf("lilly")).isEmpty();
        assertThat(nicknames.ownerOf("bobby")).isEmpty();
        assertThat(nicknames.of(ALICE)).isEmpty();
    }

    @Test
    @DisplayName("two people with the same nickname point at nobody — guessing would act on the wrong one")
    void ambiguous() {
        Nicknames nicknames = new Nicknames(database());
        nicknames.remember(ALICE, "Twin");
        nicknames.remember(BOB, "twin");

        assertThat(nicknames.ownerOf("twin")).isEmpty();
        assertThat(nicknames.ownersOf("twin")).containsExactlyInAnyOrder(ALICE, BOB);
    }

    @Test
    @DisplayName("survives a restart: what was flushed is read back")
    void persisted() {
        Nicknames before = new Nicknames(database());
        before.remember(ALICE, "Lilly Pad");
        before.remember(BOB, "Bobby");
        before.clear(BOB);
        before.flush();
        database.close();

        Nicknames after = new Nicknames(database());
        after.load();

        assertThat(after.ownerOf("lilly_pad")).contains(ALICE);
        assertThat(after.ownerOf("bobby")).isEmpty();
        assertThat(after.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("suggests the nicknames that start with what was typed, only for whoever the caller allows")
    void suggests() {
        Nicknames nicknames = new Nicknames(database());
        nicknames.remember(ALICE, "Lilly Pad");
        nicknames.remember(BOB, "Lime");

        assertThat(nicknames.suggest("li", who -> true)).containsExactlyInAnyOrder("Lilly_Pad", "Lime");
        assertThat(nicknames.suggest("lil", who -> true)).containsExactly("Lilly_Pad");
        assertThat(nicknames.suggest("", Set.of(BOB)::contains)).containsExactly("Lime");
    }

    @Test
    @DisplayName("an unusable database still answers from memory rather than throwing at a command")
    void withoutDatabase() {
        Database broken = database();
        broken.close();
        Nicknames nicknames = new Nicknames(broken);
        nicknames.load();
        nicknames.remember(ALICE, "Lilly");
        nicknames.flush();

        assertThat(nicknames.ownerOf("lilly")).contains(ALICE);
    }
}
