package de.raindancer.core.testkit;

import de.raindancer.core.data.sql.PostgresPool;
import de.raindancer.core.data.sql.PostgresSettings;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * A real PostgreSQL for tests, one per test JVM.
 *
 * <p>Where it comes from, first match wins:
 * <ol>
 *   <li>{@code RAINS_TEST_POSTGRES} = {@code host:port/database}, with {@code RAINS_TEST_POSTGRES_USER}
 *       and {@code RAINS_TEST_POSTGRES_PASSWORD} — what CI hands over from its service container;</li>
 *   <li>{@code initdb} and {@code postgres} on the PATH — a throwaway cluster under {@code target/},
 *       started on a free port and stopped when the JVM ends.</li>
 * </ol>
 * With neither, a test that needs it is skipped — unless {@code RAINS_REQUIRE_POSTGRES} or {@code CI}
 * is set, where a skipped database test would be a green build that tested nothing, so it fails.
 *
 * <p>Every caller gets its own namespace, so tests never see each other's tables.
 */
public final class TestPostgres {

    private static final AtomicInteger NAMESPACES = new AtomicInteger();
    private static volatile Optional<PostgresSettings> settings;
    private static volatile PostgresPool pool;
    private static Process local;

    private TestPostgres() {
    }

    /** The server's connection settings, starting one if need be; aborts or fails the test without one. */
    public static synchronized PostgresSettings require() {
        if (settings == null) {
            settings = start();
        }
        if (settings.isEmpty()) {
            String why = "No PostgreSQL for this test: set RAINS_TEST_POSTGRES or put initdb/postgres on the PATH.";
            if (System.getenv("RAINS_REQUIRE_POSTGRES") != null || System.getenv("CI") != null) {
                throw new AssertionError(why);
            }
            Assumptions.abort(why);
        }
        return settings.get();
    }

    /** One pool for the whole test JVM, as a server has one for all its databases. */
    public static synchronized PostgresPool pool() {
        if (pool == null) {
            pool = PostgresPool.open(require());
        }
        return pool;
    }

    /** A namespace no other test uses: {@code t_<hint>_<n>}. */
    public static String freshNamespace(String hint) {
        String clean = hint == null ? "db" : hint.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (clean.length() > 30) {
            clean = clean.substring(0, 30);
        }
        return "t_" + clean + "_" + ProcessHandle.current().pid() + "_" + NAMESPACES.incrementAndGet();
    }

    /** A plain connection outside any pool, for checking what a test left behind. */
    public static Connection connect() throws SQLException {
        Connection connection = PostgresPool.openConnection(require());
        connection.setAutoCommit(true);
        return connection;
    }

    // ------------------------------------------------------------------------------ starting

    private static Optional<PostgresSettings> start() {
        String given = System.getenv("RAINS_TEST_POSTGRES");
        if (given != null && !given.isBlank()) {
            return Optional.of(fromEnvironment(given.trim()));
        }
        Optional<Path> initdb = onPath("initdb");
        Optional<Path> postgres = onPath("postgres");
        if (initdb.isEmpty() || postgres.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(startLocal(initdb.get(), postgres.get()));
        } catch (IOException | InterruptedException failed) {
            throw new IllegalStateException("A local PostgreSQL for the tests could not be started", failed);
        }
    }

    private static PostgresSettings fromEnvironment(String given) {
        String hostPort = given.contains("/") ? given.substring(0, given.indexOf('/')) : given;
        String database = given.contains("/") ? given.substring(given.indexOf('/') + 1) : "postgres";
        String host = hostPort.contains(":") ? hostPort.substring(0, hostPort.indexOf(':')) : hostPort;
        int port = hostPort.contains(":") ? Integer.parseInt(hostPort.substring(hostPort.indexOf(':') + 1)) : 5432;
        String user = System.getenv().getOrDefault("RAINS_TEST_POSTGRES_USER", "postgres");
        String password = System.getenv().getOrDefault("RAINS_TEST_POSTGRES_PASSWORD", "");
        return PostgresSettings.of(host, port, database, user, password).withPoolSize(16).withApplicationName("rains-tests");
    }

    private static PostgresSettings startLocal(Path initdb, Path postgres) throws IOException, InterruptedException {
        Path folder = Path.of("target", "test-postgres-" + ProcessHandle.current().pid()).toAbsolutePath();
        deleteQuietly(folder);
        Files.createDirectories(folder.getParent());
        run(List.of(initdb.toString(), "-D", folder.toString(), "-U", "rains", "-A", "trust",
                "-E", "UTF8", "--locale=C", "--no-sync"), folder.resolveSibling(folder.getFileName() + ".initdb.log"));
        int port = freePort();
        ProcessBuilder builder = new ProcessBuilder(postgres.toString(), "-D", folder.toString(),
                "-p", String.valueOf(port),
                "-c", "listen_addresses=127.0.0.1",
                "-c", "unix_socket_directories=",
                "-c", "fsync=off",
                "-c", "synchronous_commit=off",
                "-c", "full_page_writes=off",
                "-c", "max_connections=300")
                .redirectErrorStream(true)
                .redirectOutput(folder.resolveSibling(folder.getFileName() + ".log").toFile());
        local = builder.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (pool != null) {
                pool.close();
            }
            local.destroy();
            try {
                local.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            local.destroyForcibly();
            deleteQuietly(folder);
        }, "test-postgres-stop"));
        PostgresSettings started = PostgresSettings.of("127.0.0.1", port, "postgres", "rains", "")
                .withPoolSize(16).withApplicationName("rains-tests");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            try (Connection ignored = PostgresPool.openConnection(started)) {
                return started;
            } catch (SQLException notYet) {
                if (!local.isAlive() || System.nanoTime() > deadline) {
                    throw new IOException("PostgreSQL did not come up; see " + folder + ".log", notYet);
                }
                Thread.sleep(100);
            }
        }
    }

    private static void run(List<String> command, Path log) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException(command.getFirst() + " failed; see " + log);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Optional<Path> onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        Stream<Path> candidates = Stream.concat(
                Stream.of(path.split(java.io.File.pathSeparator)).map(Path::of),
                versionedPostgresFolders());
        return candidates.map(folder -> folder.resolve(program)).filter(Files::isExecutable).findFirst();
    }

    /** Debian and Ubuntu keep the server programs off the PATH, under /usr/lib/postgresql/<n>/bin. */
    private static Stream<Path> versionedPostgresFolders() {
        Path root = Path.of("/usr/lib/postgresql");
        if (!Files.isDirectory(root)) {
            return Stream.empty();
        }
        try (Stream<Path> versions = Files.list(root)) {
            return versions.sorted(Comparator.reverseOrder()).map(version -> version.resolve("bin")).toList().stream();
        } catch (IOException unreadable) {
            return Stream.empty();
        }
    }

    private static void deleteQuietly(Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(each -> {
                try {
                    Files.deleteIfExists(each);
                } catch (IOException ignored) {
                    // a test cluster left behind under target/ is cleaned by the next mvn clean
                }
            });
        } catch (IOException ignored) {
            // as above
        }
    }
}
