package de.raindancer.core.ui.tablist;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.ui.identity.Identities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText;
import static org.assertj.core.api.Assertions.assertThat;

/** Who is on the other servers of the network, under the footer — everybody is somewhere on the list. */
@org.junit.jupiter.api.Tag("database")
class TablistElsewhereTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("one line per server with somebody on it: its name, how many, who — sorted; nobody elsewhere adds nothing")
    void lines() {
        TablistModel model = new TablistModel(new Identities(TestDatabases.open(directory.resolve("core.db"), CoreSchema.CORE,
                () -> false)));
        Map<String, Set<String>> elsewhere = new LinkedHashMap<>();
        elsewhere.put("Arena", Set.of("Engine_963", "Bo"));
        elsewhere.put("Hub", Set.of());

        String text = plainText().serialize(model.elsewhere(elsewhere));

        assertThat(text).contains("Arena").contains("(2)").contains("Bo, Engine_963").doesNotContain("Hub");
        assertThat(plainText().serialize(model.elsewhere(Map.of()))).isEmpty();
    }
}
