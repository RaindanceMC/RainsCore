package de.raindancer.core.ui.identity;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.sql.CoreSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** One server's idea of a player's name, carried to another as plain strings and taken over whole there. */
@org.junit.jupiter.api.Tag("database")
class IdentityTransferTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("exported on survival, adopted in the arena: prefix, colour, subtitle and nickname arrive; what is gone there goes")
    void roundTrip() {
        UUID ada = UUID.randomUUID();
        Identities survival = new Identities(TestDatabases.open(directory.resolve("a.db"), CoreSchema.CORE, () -> false));
        survival.setPrefix(ada, "<gold>[VIP] ");
        survival.setColour(ada, "#ff00aa");
        survival.setSubtitle(ada, "<gray>the brave");
        survival.setNickname(ada, "Addie");

        Identities arena = new Identities(TestDatabases.open(directory.resolve("b.db"), CoreSchema.CORE, () -> false));
        arena.setSuffix(ada, " <red>old");
        Map<String, String> carried = survival.export(ada);
        arena.adopt(ada, carried);

        assertThat(arena.prefix(ada)).contains("<gold>[VIP] ");
        assertThat(arena.colour(ada).orElseThrow()).isEqualToIgnoringCase("#ff00aa");
        assertThat(arena.subtitle(ada)).isPresent();
        assertThat(arena.shownName(ada, "Ada")).isEqualTo("Addie");
        assertThat(arena.suffix(ada)).as("cleared on survival, so cleared here").isEmpty();
    }
}
