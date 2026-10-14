package de.raindancer.core.platform.network;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class NetworkRolesTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<Database> databases = new ArrayList<>();

    private NetworkRoles server(String id) {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        return new NetworkRoles(database, id, clock::get);
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("one holder: the first to claim keeps it while it renews")
    void oneHolder() {
        NetworkRoles smp = server("smp");
        NetworkRoles arena = server("arena");
        assertThat(smp.claim("economy")).isTrue();
        assertThat(arena.claim("economy")).isFalse();
        clock.addAndGet(NetworkRoles.LEASE_MILLIS / 2);
        assertThat(smp.claim("economy")).as("renewed").isTrue();
        clock.addAndGet(NetworkRoles.LEASE_MILLIS / 2);
        assertThat(arena.claim("economy")).isFalse();
        assertThat(arena.holder("economy")).contains("smp");
    }

    @Test
    @DisplayName("a holder that stops renewing is replaced once its lease runs out")
    void takenOver() {
        NetworkRoles smp = server("smp");
        NetworkRoles arena = server("arena");
        smp.claim("economy");
        clock.addAndGet(NetworkRoles.LEASE_MILLIS + 1);
        assertThat(arena.holder("economy")).isEmpty();
        assertThat(arena.claim("economy")).isTrue();
        assertThat(smp.claim("economy")).as("it came back too late").isFalse();
    }

    @Test
    @DisplayName("released at shutdown, another takes it at once")
    void released() {
        NetworkRoles smp = server("smp");
        NetworkRoles arena = server("arena");
        smp.claim("lottery");
        smp.release("lottery");
        assertThat(arena.claim("lottery")).isTrue();
    }

    @Test
    @DisplayName("many servers claiming at the same moment: exactly one holds it")
    void race() throws Exception {
        List<NetworkRoles> servers = new ArrayList<>();
        for (int at = 0; at < 6; at++) {
            servers.add(server("s" + at));
        }
        ExecutorService threads = Executors.newFixedThreadPool(6);
        List<Future<Boolean>> claims = new ArrayList<>();
        for (NetworkRoles each : servers) {
            claims.add(threads.submit((Callable<Boolean>) () -> each.claim("economy")));
        }
        int holders = 0;
        for (Future<Boolean> claim : claims) {
            holders += claim.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        threads.shutdownNow();
        assertThat(holders).isEqualTo(1);
    }
}
