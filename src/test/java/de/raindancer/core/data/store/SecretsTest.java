package de.raindancer.core.data.store;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
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

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class SecretsTest {

    @TempDir
    Path folder;

    private final List<Database> databases = new ArrayList<>();

    private Secrets server() {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        return new Secrets(database, System::currentTimeMillis);
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("made once, the same for every server that asks")
    void sharedAcrossServers() {
        byte[] first = server().bytes("address-hash", 32, null).orElseThrow();
        byte[] second = server().bytes("address-hash", 32, null).orElseThrow();
        assertThat(first).hasSize(32).isEqualTo(second);
        assertThat(server().bytes("other", 32, null).orElseThrow()).isNotEqualTo(first);
    }

    @Test
    @DisplayName("servers asking at the same moment all get the one secret")
    void concurrent() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(6);
        List<Future<byte[]>> asked = new ArrayList<>();
        for (int at = 0; at < 6; at++) {
            Secrets each = server();
            asked.add(threads.submit(() -> each.bytes("seal", 32, null).orElseThrow()));
        }
        byte[] first = asked.getFirst().get(30, TimeUnit.SECONDS);
        for (Future<byte[]> each : asked) {
            assertThat(each.get(30, TimeUnit.SECONDS)).isEqualTo(first);
        }
        threads.shutdownNow();
    }

    @Test
    @DisplayName("a 1.x key file becomes the network's secret and is kept renamed")
    void importsTheOldFile() throws Exception {
        Path old = folder.resolve("address-hash.key");
        byte[] key = new byte[32];
        key[0] = 42;
        Files.write(old, key);
        assertThat(server().bytes("address-hash", 32, old)).contains(key);
        assertThat(old).doesNotExist();
        assertThat(old.resolveSibling("address-hash.key.imported-2.0.0")).exists();
    }

    @Test
    @DisplayName("a second server's own old key does not replace the network's; its file is kept too")
    void secondFileDoesNotWin() throws Exception {
        byte[] network = server().bytes("address-hash", 32, null).orElseThrow();
        Path old = folder.resolve("other.key");
        Files.write(old, new byte[32]);
        assertThat(server().bytes("address-hash", 32, old)).contains(network);
        assertThat(old.resolveSibling("other.key.imported-2.0.0")).exists();
    }

    @Test
    @DisplayName("no database, no secret — and no exception")
    void unreachable() throws Exception {
        Database broken = TestDatabases.unusable(folder.resolve("broken.db"), CoreSchema.CORE);
        assertThat(new Secrets(broken, System::currentTimeMillis).bytes("x", 32, null)).isEmpty();
        broken.close();
    }
}
