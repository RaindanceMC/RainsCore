package de.raindancer.core.data.store;

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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class KeyedDocumentTest {

    @TempDir
    Path plugins;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();

    /** A player's coins: an int per key. */
    private KeyedDocument<Integer> server(String id) {
        Database database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        databases.add(database);
        Documents documents = new Documents(database, hub.bus(id), plugins, id, System::currentTimeMillis);
        KeyedDocument<Integer> coins = new KeyedDocument<>(
                new YamlStore(plugins.resolve("RainsTest").resolve("coins.yml"), documents, Documents.Scope.NETWORK),
                "coins", (yaml, path) -> yaml.isInt(path) ? yaml.getInt(path) : null, (yaml, path, value) -> yaml.set(path, value));
        coins.load();
        return coins;
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("changed from what the database holds: two servers adding to one key at once both count")
    void bothCount() {
        KeyedDocument<Integer> smp = server("smp");
        KeyedDocument<Integer> arena = server("arena");
        assertThat(smp.change("alex", now -> now == null ? 1 : now + 1)).isTrue();
        assertThat(arena.change("alex", now -> now == null ? 1 : now + 1)).as("arena had not heard").isTrue();
        assertThat(arena.get("alex")).contains(2);
        hub.drain();
        assertThat(smp.get("alex")).contains(2);
    }

    @Test
    @DisplayName("other keys are never written back from a stale copy; null removes a key")
    void onlyTheKey() {
        KeyedDocument<Integer> smp = server("smp");
        KeyedDocument<Integer> arena = server("arena");
        smp.change("alex", now -> 5);
        arena.change("bea", now -> 7);
        arena.change("alex", now -> null);
        hub.drain();
        assertThat(smp.all()).containsOnlyKeys("bea");
        assertThat(server("hub").get("bea")).contains(7);
    }

    @Test
    @DisplayName("another server's change is heard, with the keys that changed")
    void heard() {
        KeyedDocument<Integer> smp = server("smp");
        KeyedDocument<Integer> arena = server("arena");
        List<Set<String>> heard = new ArrayList<>();
        smp.onChange(heard::add);
        arena.change("alex", now -> 3);
        hub.drain();
        assertThat(heard).containsExactly(Set.of("alex"));
        smp.change("bea", now -> 1);
        hub.drain();
        assertThat(heard).as("not for its own").hasSize(1);
    }

    @Test
    @DisplayName("a document that cannot be read keeps what is known and refuses every change")
    void unreadable() {
        Database database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        databases.add(database);
        new Documents(database, hub.bus("setup"), plugins, "setup", System::currentTimeMillis).insertIfAbsent(
                new Documents.Address(Documents.NETWORK_SCOPE, "RainsTest", "coins.yml"), "coins: [unclosed\n");
        KeyedDocument<Integer> smp = server("smp");
        assertThat(smp.readable()).isFalse();
        assertThat(smp.change("alex", now -> 1)).isFalse();
    }
}
