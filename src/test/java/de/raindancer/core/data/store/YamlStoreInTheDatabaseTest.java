package de.raindancer.core.data.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.testkit.TestDatabases;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class YamlStoreInTheDatabaseTest {

    @TempDir
    Path plugins;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();

    /** One server: its own Documents over the shared database. */
    private Documents server(String id) {
        Database database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        databases.add(database);
        return new Documents(database, hub.bus(id), plugins, id, System::currentTimeMillis);
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    private Path file(String owner, String name) {
        return plugins.resolve(owner).resolve(name);
    }

    @Test
    @DisplayName("written and read back from the database; no file appears")
    void roundTrip() {
        YamlStore store = new YamlStore(file("RainsJobs", "jobs.yml"), server("smp"), Documents.Scope.SERVER);
        assertThat(store.inDatabase()).isTrue();
        assertThat(store.exists()).isFalse();
        assertThat(store.write(yaml -> yaml.set("goal.wheat", 640))).isTrue();
        assertThat(store.exists()).isTrue();
        assertThat(store.read().getInt("goal.wheat")).isEqualTo(640);
        assertThat(file("RainsJobs", "jobs.yml")).doesNotExist();
    }

    @Test
    @DisplayName("a 1.x text file (not .yml) is brought in as it is, even when it is no YAML at all")
    void importsAnOldTextFile() throws Exception {
        Path old = file("RainsPerformanceOptimizer", "reports/report-31.txt");
        Files.createDirectories(old.getParent());
        String report = "Report #31 — one tick took 1306 ms\n  [Server thread] at: something: [unclosed\n";
        Files.writeString(old, report);
        YamlStore store = new YamlStore(old, server("smp"), Documents.Scope.SERVER);

        assertThat(store.readText()).hasValue(report);
        assertThat(old).doesNotExist();
        assertThat(old.resolveSibling("report-31.txt.imported-2.0.0")).exists();
    }

    @Test
    @DisplayName("a 1.x file is brought in on first use, comments and all, and kept renamed, never deleted")
    void importsTheOldFile() throws Exception {
        Path old = file("RainsEssentials", "rules.yml");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "# The owner's own comment\nrules:\n  - be kind\n");
        YamlStore store = new YamlStore(old, server("smp"), Documents.Scope.SERVER);

        YamlConfiguration read = store.read();

        assertThat(read.getStringList("rules")).containsExactly("be kind");
        assertThat(old).doesNotExist();
        assertThat(old.resolveSibling("rules.yml.imported-2.0.0")).exists();
        assertThat(read.saveToString()).contains("# The owner's own comment");
    }

    @Test
    @DisplayName("a second server's own file for a network document is merged in: what is there wins, the rest is added")
    void secondServersFileIsMerged() throws Exception {
        Documents smp = server("smp");
        YamlStore first = new YamlStore(file("RainsEssentials", "nicknames.yml"), smp, Documents.Scope.NETWORK);
        first.write(yaml -> {
            yaml.set("nick.alex", "Alexandra");
            yaml.set("nick.bea", "Bee");
        });
        Path arenasFile = plugins.resolve("arena-folder");
        Documents arena = new Documents(databases.getFirst(), hub.bus("arena"), arenasFile, "arena", System::currentTimeMillis);
        Path old = arenasFile.resolve("RainsEssentials").resolve("nicknames.yml");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "nick:\n  alex: Lexi\n  carl: Carlo\n");

        YamlConfiguration merged = new YamlStore(old, arena, Documents.Scope.NETWORK).read();

        assertThat(merged.getString("nick.alex")).isEqualTo("Alexandra");
        assertThat(merged.getString("nick.bea")).isEqualTo("Bee");
        assertThat(merged.getString("nick.carl")).isEqualTo("Carlo");
        assertThat(old).doesNotExist();
    }

    @Test
    @DisplayName("each server has its own document of the same name; the network's is shared")
    void scopes() {
        Documents smp = server("smp");
        Documents arena = server("arena");
        Path config = file("RainsChat", "config.yml");
        new YamlStore(config, smp, Documents.Scope.SERVER).write(yaml -> yaml.set("format", "smp"));
        new YamlStore(config, arena, Documents.Scope.SERVER).write(yaml -> yaml.set("format", "arena"));
        assertThat(new YamlStore(config, smp, Documents.Scope.SERVER).read().getString("format")).isEqualTo("smp");
        assertThat(new YamlStore(config, arena, Documents.Scope.SERVER).read().getString("format")).isEqualTo("arena");

        Path shared = file("RainsChat", "styles.yml");
        new YamlStore(shared, smp, Documents.Scope.NETWORK).write(yaml -> yaml.set("alex", "gold"));
        assertThat(new YamlStore(shared, arena, Documents.Scope.NETWORK).read().getString("alex")).isEqualTo("gold");
    }

    @Test
    @DisplayName("two servers updating different keys of one network document at once: every change lands")
    void concurrentUpdates() throws Exception {
        Path shared = file("RainsJobs", "quest-progress.yml");
        YamlStore smp = new YamlStore(shared, server("smp"), Documents.Scope.NETWORK);
        YamlStore arena = new YamlStore(shared, server("arena"), Documents.Scope.NETWORK);
        ExecutorService threads = Executors.newFixedThreadPool(4);
        AtomicInteger failed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int at = 0; at < 40; at++) {
            int n = at;
            futures.add(threads.submit(() -> {
                if (!smp.update(yaml -> yaml.set("smp.p" + n, n))) {
                    failed.incrementAndGet();
                }
            }));
            futures.add(threads.submit(() -> {
                if (!arena.update(yaml -> yaml.set("arena.p" + n, n))) {
                    failed.incrementAndGet();
                }
            }));
        }
        for (Future<?> each : futures) {
            each.get(60, TimeUnit.SECONDS);
        }
        threads.shutdownNow();
        assertThat(failed).hasValue(0);
        YamlConfiguration result = smp.read();
        assertThat(result.getConfigurationSection("smp").getKeys(false)).hasSize(40);
        assertThat(result.getConfigurationSection("arena").getKeys(false)).hasSize(40);
    }

    @Test
    @DisplayName("a whole write over a document somebody changed since it was read keeps their change")
    void writeMergesOverAnotherWrite() {
        Path shared = file("RainsChat", "mentions.yml");
        YamlStore smp = new YamlStore(shared, server("smp"), Documents.Scope.NETWORK);
        YamlStore arena = new YamlStore(shared, server("arena"), Documents.Scope.NETWORK);
        smp.write(yaml -> yaml.set("alex", 1));
        YamlConfiguration seenBySmp = smp.read();
        arena.read();
        arena.update(yaml -> yaml.set("bea", 2));

        // smp writes the whole document from what it read, plus its own change.
        smp.write(yaml -> {
            seenBySmp.getValues(true).forEach((key, value) -> {
                if (!(value instanceof org.bukkit.configuration.ConfigurationSection)) {
                    yaml.set(key, value);
                }
            });
            yaml.set("carl", 3);
        });

        YamlConfiguration now = arena.read();
        assertThat(now.getInt("alex")).isEqualTo(1);
        assertThat(now.getInt("bea")).as("arena's change survives smp's whole write").isEqualTo(2);
        assertThat(now.getInt("carl")).isEqualTo(3);
    }

    @Test
    @DisplayName("another server writing a network document tells the stores watching it here")
    void changesAreHeard() {
        Path shared = file("RainsRanks", "ranks.yml");
        YamlStore smp = new YamlStore(shared, server("smp"), Documents.Scope.NETWORK);
        YamlStore arena = new YamlStore(shared, server("arena"), Documents.Scope.NETWORK);
        AtomicInteger heard = new AtomicInteger();
        arena.onChange(heard::incrementAndGet);
        smp.write(yaml -> yaml.set("vip.weight", 10));
        hub.drain();
        assertThat(heard).hasValue(1);
    }

    @Test
    @DisplayName("a database nobody can reach: reads are empty and say why, and nothing is written over")
    void unreachable() throws Exception {
        Database broken = TestDatabases.unusable(plugins.resolve("broken.db"), Documents.SCHEMA);
        Documents documents = new Documents(broken, null, plugins, "smp", System::currentTimeMillis);
        YamlStore store = new YamlStore(file("RainsJobs", "jobs.yml"), documents, Documents.Scope.SERVER);
        assertThat(store.read().getKeys(false)).isEmpty();
        assertThat(store.problems()).isNotEmpty();
        assertThat(store.update(yaml -> yaml.set("x", 1))).isFalse();
        broken.close();
    }

    @Test
    @DisplayName("a file outside the plugins folder stays a file")
    void outsideStaysAFile() throws Exception {
        Path elsewhere = plugins.getParent().resolve("elsewhere-" + System.nanoTime() + ".yml");
        try {
            YamlStore store = new YamlStore(elsewhere, server("smp"), Documents.Scope.SERVER);
            assertThat(store.inDatabase()).isFalse();
            store.write(yaml -> yaml.set("a", 1));
            assertThat(elsewhere).exists();
        } finally {
            Files.deleteIfExists(elsewhere);
        }
    }

    @Test
    @DisplayName("a document that is not valid YAML is set aside, not written over")
    void brokenDocumentIsKept() {
        Documents documents = server("smp");
        Path config = file("RainsJobs", "broken.yml");
        Documents.Address address = documents.addressOf(config, Documents.Scope.SERVER).orElseThrow();
        documents.insertIfAbsent(address, "this: is: not: yaml: [");
        YamlStore store = new YamlStore(config, documents, Documents.Scope.SERVER);
        assertThat(store.read().getKeys(false)).isEmpty();
        assertThat(store.update(yaml -> yaml.set("x", 1))).isFalse();
        assertThat(store.write(yaml -> yaml.set("x", 1))).isTrue();
        assertThat(documents.list(address.scope(), "RainsJobs", "broken.yml.broken-")).hasSize(1);
        assertThat(store.read().getInt("x")).isEqualTo(1);
    }

    @Test
    @DisplayName("documents in a folder can be listed, as files in a folder could")
    void listsAFolder() {
        Documents documents = server("smp");
        for (String id : List.of("a", "b", "c")) {
            new YamlStore(file("RainsExtendedClaims", "claims/" + id + ".yml"), documents, Documents.Scope.SERVER)
                    .write(yaml -> yaml.set("id", id));
        }
        new YamlStore(file("RainsExtendedClaims", "zones.yml"), documents, Documents.Scope.SERVER).write(yaml -> yaml.set("z", 1));
        assertThat(documents.list("smp", "RainsExtendedClaims", "claims/"))
                .containsExactly("claims/a.yml", "claims/b.yml", "claims/c.yml");
    }
}
