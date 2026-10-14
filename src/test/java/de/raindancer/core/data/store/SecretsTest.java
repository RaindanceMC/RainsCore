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

    @Test
    @DisplayName("an old key file holding text (base64) is decoded on import: the network's key is the same bytes")
    void decodesATextFile() throws Exception {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        Path file = folder.resolve("cash.key");
        java.nio.file.Files.writeString(file, java.util.Base64.getEncoder().encodeToString(key) + "\n");
        assertThat(server().bytes("cash", 32, file, Secrets.BASE64)).hasValueSatisfying(got -> assertThat(got).isEqualTo(key));
        assertThat(server().bytes("cash", 32, null)).hasValueSatisfying(got -> assertThat(got).isEqualTo(key));
    }

    @Test
    @DisplayName("an old key file that does not decode is not imported: a fresh key, the file kept")
    void undecodableFile() throws Exception {
        Path file = folder.resolve("cash.key");
        java.nio.file.Files.writeString(file, "not base64 at all !!!");
        assertThat(server().bytes("cash", 32, file, Secrets.BASE64)).hasValueSatisfying(got -> assertThat(got).hasSize(32));
        assertThat(file.resolveSibling("cash.key.imported-2.0.0")).exists();
    }

    @Test
    @DisplayName("a secret an owner gives (a login, a bot token): stored, found by every server, replaced, removed")
    void givenByTheOwner() {
        Secrets smp = server();
        Secrets arena = server();
        assertThat(smp.text("twitch-login")).isEmpty();
        assertThat(smp.putText("twitch-login", "id:secret")).isTrue();
        assertThat(arena.text("twitch-login")).contains("id:secret");
        assertThat(arena.putText("twitch-login", "id:other")).isTrue();
        assertThat(smp.text("twitch-login")).contains("id:other");
        assertThat(smp.remove("twitch-login")).isTrue();
        assertThat(arena.text("twitch-login")).isEmpty();
    }

    @Test
    @DisplayName("an unreachable database finds nothing and stores nothing")
    void givenUnreachable() {
        Secrets nowhere = new Secrets(null, System::currentTimeMillis);
        assertThat(nowhere.text("x")).isEmpty();
        assertThat(nowhere.putText("x", "y")).isFalse();
    }
}
