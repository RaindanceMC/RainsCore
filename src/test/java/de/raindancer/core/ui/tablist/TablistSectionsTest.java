package de.raindancer.core.ui.tablist;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.text.Text;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A section — the server's team — listed on its own, above every world. */
@org.junit.jupiter.api.Tag("database")
class TablistSectionsTest {

    @TempDir
    Path folder;

    private TablistModel model() {
        return new TablistModel(new Identities(TestDatabases.open(
                folder.resolve("identities.db"), CoreSchema.CORE, () -> false)));
    }

    private static TablistEntry player(String name, String world) {
        return new TablistEntry(UUID.nameUUIDFromBytes(name.getBytes()), name, world, 40);
    }

    private static final TablistEntry TOM = player("Tom", "world_nether");
    private static final TablistEntry ALICE = player("Alice", "world");
    private static final TablistEntry BOB = player("Bob", "world");

    @Test
    @DisplayName("without a section nothing changes")
    void none() {
        TablistModel model = model();
        assertThat(model.groups(List.of(TOM, ALICE)).stream().map(TablistGroup::label)).containsExactly("Overworld", "Nether");
    }

    @Test
    @DisplayName("team members sort above every world, and are their own group in the footer")
    void team() {
        TablistModel model = model();
        model.sectionOf(entry -> entry.equals(TOM) ? Optional.of("Team") : Optional.empty());

        assertThat(model.sortKey(TOM)).isLessThan(model.sortKey(ALICE)).isLessThan(model.sortKey(BOB));
        List<TablistGroup> groups = model.groups(List.of(ALICE, TOM, BOB));
        assertThat(groups.stream().map(TablistGroup::label)).containsExactly("Team", "Overworld");
        assertThat(groups.getFirst().entries()).containsExactly(TOM);
        assertThat(Text.plain(model.footer(List.of(ALICE, TOM, BOB)))).contains("Team").contains("1");
    }

    @Test
    @DisplayName("a section function that throws is no section, never an empty tablist")
    void throwing() {
        TablistModel model = model();
        model.sectionOf(entry -> {
            throw new IllegalStateException("gone");
        });
        assertThat(model.groups(List.of(ALICE)).stream().map(TablistGroup::label)).containsExactly("Overworld");
        model.sectionOf(null);
        assertThat(model.sortKey(ALICE)).startsWith("a");
    }
}
