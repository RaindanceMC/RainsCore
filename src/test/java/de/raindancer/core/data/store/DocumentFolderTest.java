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

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class DocumentFolderTest {

    @TempDir
    Path plugins;

    private Database database;
    private Documents documents;

    @BeforeEach
    void open() {
        database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        documents = new Documents(database, null, plugins, "smp", System::currentTimeMillis);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Path claims() {
        return plugins.resolve("RainsExtendedClaims").resolve("claims");
    }

    @Test
    @DisplayName("lists what is in the database and the 1.x files not brought in yet; opening one brings it in")
    void listsBothAndBringsIn() throws Exception {
        DocumentFolder folder = DocumentFolder.on(claims(), documents, Documents.Scope.SERVER);
        folder.store("a.yml").write(yaml -> yaml.set("id", "a"));
        Files.createDirectories(claims());
        Files.writeString(claims().resolve("b.yml"), "id: b\n");
        Files.writeString(claims().resolve("notes.txt"), "not a document of this folder");

        assertThat(folder.names(".yml")).containsExactly("a.yml", "b.yml");
        assertThat(folder.store("b.yml").read().getString("id")).isEqualTo("b");
        assertThat(claims().resolve("b.yml")).doesNotExist();
        assertThat(folder.names(".yml")).containsExactly("a.yml", "b.yml");
    }

    @Test
    @DisplayName("only the folder's own entries: not those of a folder inside it, not a sibling's")
    void onlyItsOwn() {
        DocumentFolder folder = DocumentFolder.on(claims(), documents, Documents.Scope.SERVER);
        folder.store("a.yml").write(yaml -> yaml.set("x", 1));
        DocumentFolder.on(claims().resolve("old"), documents, Documents.Scope.SERVER).store("z.yml").write(yaml -> yaml.set("x", 1));
        new YamlStore(plugins.resolve("RainsExtendedClaims").resolve("claims-zones.yml"), documents, Documents.Scope.SERVER)
                .write(yaml -> yaml.set("x", 1));
        assertThat(folder.names(".yml")).containsExactly("a.yml");
    }

    @Test
    @DisplayName("an entry deleted is gone from the list, the database and the disk")
    void delete() throws Exception {
        DocumentFolder folder = DocumentFolder.on(claims(), documents, Documents.Scope.SERVER);
        folder.store("a.yml").write(yaml -> yaml.set("x", 1));
        Files.createDirectories(claims());
        Files.writeString(claims().resolve("c.yml"), "x: 1\n");
        assertThat(folder.delete("a.yml")).isTrue();
        assertThat(folder.delete("c.yml")).isTrue();
        assertThat(folder.names(".yml")).isEmpty();
    }

    @Test
    @DisplayName("the network's folder and this server's are different folders")
    void scopes() {
        DocumentFolder.on(claims(), documents, Documents.Scope.NETWORK).store("n.yml").write(yaml -> yaml.set("x", 1));
        assertThat(DocumentFolder.on(claims(), documents, Documents.Scope.SERVER).names(".yml")).isEmpty();
        assertThat(DocumentFolder.on(claims(), documents, Documents.Scope.NETWORK).names(".yml")).containsExactly("n.yml");
    }

    @Test
    @DisplayName("without documents it is a plain folder of files")
    void filesWithoutCore() throws Exception {
        DocumentFolder folder = DocumentFolder.on(claims(), null, Documents.Scope.SERVER);
        folder.store("a.yml").write(yaml -> yaml.set("x", 1));
        assertThat(claims().resolve("a.yml")).exists();
        assertThat(folder.names(".yml")).containsExactly("a.yml");
        assertThat(folder.delete("a.yml")).isTrue();
        assertThat(claims().resolve("a.yml")).doesNotExist();
    }

    @Test
    @DisplayName("a name that leaves the folder is refused")
    void noEscape() {
        DocumentFolder folder = DocumentFolder.on(claims(), documents, Documents.Scope.SERVER);
        assertThat(folder.delete("../../x.yml")).isFalse();
        assertThat(folder.delete("..\\config.yml")).as("a backslash is a separator to a document's name").isFalse();
        assertThat(folder.delete("..")).isFalse();
        assertThat(folder.delete("a\u0000.yml")).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> folder.store("../x.yml"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
