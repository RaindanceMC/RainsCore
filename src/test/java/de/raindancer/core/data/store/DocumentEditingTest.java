package de.raindancer.core.data.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class DocumentEditingTest {

    @TempDir
    Path plugins;

    private Database database;
    private Documents documents;
    private DocumentEditing editing;

    @BeforeEach
    void open() {
        database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        documents = new Documents(database, null, plugins, "smp", System::currentTimeMillis);
        editing = new DocumentEditing(documents, plugins.resolve("RainsCore").resolve("edit"));
    }

    @AfterEach
    void close() {
        database.close();
    }

    private YamlStore store(String owner, String name, Documents.Scope scope) {
        return new YamlStore(plugins.resolve(owner).resolve(name), documents, scope);
    }

    @Test
    @DisplayName("lists this server's documents and the network's, by plugin")
    void lists() {
        store("RainsChat", "config.yml", Documents.Scope.SERVER).write(yaml -> yaml.set("a", 1));
        store("RainsCore", "messages.yml", Documents.Scope.NETWORK).write(yaml -> yaml.set("b", 2));
        assertThat(editing.list()).containsExactly(
                new DocumentEditing.Entry("network", "RainsCore", "messages.yml"),
                new DocumentEditing.Entry("server", "RainsChat", "config.yml"));
    }

    @Test
    @DisplayName("exported as a file, edited by hand, imported back: the stores watching it reload")
    void roundTrip() throws Exception {
        YamlStore config = store("RainsChat", "config.yml", Documents.Scope.SERVER);
        config.write(yaml -> yaml.set("format", "<name>: <message>"));
        AtomicInteger reloads = new AtomicInteger();
        store("RainsChat", "config.yml", Documents.Scope.SERVER).onChange(reloads::incrementAndGet);

        Path exported = editing.export("RainsChat", "config.yml").orElseThrow();
        assertThat(exported).isEqualTo(plugins.resolve("RainsCore/edit/server/RainsChat/config.yml"));
        Files.writeString(exported, Files.readString(exported).replace("<name>", "<gold><name></gold>"));

        DocumentEditing.Imported result = editing.importAll();

        assertThat(result.applied()).containsExactly("server/RainsChat/config.yml");
        assertThat(result.refused()).isEmpty();
        assertThat(config.read().getString("format")).isEqualTo("<gold><name></gold>: <message>");
        assertThat(reloads).hasValue(1);
        assertThat(exported).doesNotExist();
        try (var applied = Files.walk(plugins.resolve("RainsCore/edit/applied"))) {
            assertThat(applied.filter(Files::isRegularFile)).hasSize(1);
        }
    }

    @Test
    @DisplayName("a network document is found and written back as the network's")
    void networkDocument() throws Exception {
        store("RainsCore", "messages.yml", Documents.Scope.NETWORK).write(yaml -> yaml.set("hello", "hi"));
        Path exported = editing.export("RainsCore", "messages.yml").orElseThrow();
        assertThat(exported.toString()).contains("/network/");
        Files.writeString(exported, "hello: moin\n");
        editing.importAll();
        assertThat(store("RainsCore", "messages.yml", Documents.Scope.NETWORK).read().getString("hello")).isEqualTo("moin");
    }

    @Test
    @DisplayName("a file that is not valid YAML is refused and left where it is; the document is untouched")
    void invalidRefused() throws Exception {
        store("RainsJobs", "jobs.yml", Documents.Scope.SERVER).write(yaml -> yaml.set("ok", true));
        Path exported = editing.export("RainsJobs", "jobs.yml").orElseThrow();
        Files.writeString(exported, "ok: [unclosed\n");
        DocumentEditing.Imported result = editing.importAll();
        assertThat(result.refused()).containsKey("server/RainsJobs/jobs.yml");
        assertThat(exported).exists();
        assertThat(store("RainsJobs", "jobs.yml", Documents.Scope.SERVER).read().getBoolean("ok")).isTrue();
    }

    @Test
    @DisplayName("exporting something that does not exist says so")
    void missing() {
        assertThat(editing.export("Nobody", "nothing.yml")).isEmpty();
    }

    @Test
    @DisplayName("a path that tries to leave the edit folder is refused")
    void noEscape() throws Exception {
        Path sneaky = plugins.resolve("RainsCore/edit/server/../../../evil.yml");
        Files.createDirectories(plugins.resolve("RainsCore/edit/server"));
        assertThat(editing.export("..", "../../evil.yml")).isEmpty();
        assertThat(sneaky.normalize()).doesNotExist();
    }
}
