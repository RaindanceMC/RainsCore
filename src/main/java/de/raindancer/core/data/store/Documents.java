package de.raindancer.core.data.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.sql.Schema;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.network.NetworkBus;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * Where YAML documents live once Core runs (2.0.0): rows in the database, not files beside the plugin.
 *
 * <p>A {@link YamlStore} still names its document by a file — {@code plugins/RainsJobs/quests.yml} — and
 * that file becomes an address: the plugin's folder is the owner, the rest of the path the name. Nothing
 * a store does changes, which is what lets every plugin built on {@code YamlStore} move with no code of
 * its own.
 *
 * <h2>Scope</h2>
 * A document is either this server's ({@link Scope#SERVER}, the default — each server keeps its own,
 * exactly as each kept its own file) or the whole network's ({@link Scope#NETWORK}, one document every
 * server reads and writes). On a server of its own both are simply its documents.
 *
 * <h2>Writing</h2>
 * Every document carries a version. A write states the version it read; if another writer got there
 * first the write is merged value by value ({@link YamlMerge}) onto what is there now and tried again —
 * two servers changing different players in one document both keep their change.
 */
public final class Documents {

    private static final LogChannel log = Log.of("store");

    public static final String NAMESPACE = "documents";
    public static final String TOPIC = "document";

    /** The scope value of the network's documents; a server's are its id, or "" on a server of its own. */
    static final String NETWORK_SCOPE = "*";

    public static final Schema SCHEMA = Schema.of("""
            CREATE TABLE document (
                scope      TEXT    NOT NULL,
                owner      TEXT    NOT NULL,
                name       TEXT    NOT NULL,
                body       TEXT    NOT NULL,
                version    INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                updated_by TEXT,
                PRIMARY KEY (scope, owner, name)
            )""");

    public enum Scope { SERVER, NETWORK }

    /** Where one document is: whose, and under which name. */
    public record Address(String scope, String owner, String name) {
        public String key() {
            return scope + "|" + owner + "|" + name;
        }

        static Optional<Address> ofKey(String key) {
            String[] parts = key == null ? new String[0] : key.split("\\|", 3);
            return parts.length == 3 ? Optional.of(new Address(parts[0], parts[1], parts[2])) : Optional.empty();
        }
    }

    /** What reading a document found. */
    public sealed interface Read permits Found, Absent, Failed {
    }

    public record Found(String body, long version) implements Read {
    }

    public record Absent() implements Read {
    }

    public record Failed(String why) implements Read {
    }

    private static volatile Documents installed;

    private final Database database;
    private final NetworkBus bus;
    private final Path root;
    private final String serverScope;
    private final LongSupplier clock;
    private final Map<String, List<Runnable>> listeners = new ConcurrentHashMap<>();
    private final Set<String> imported = ConcurrentHashMap.newKeySet();

    /**
     * @param root        the server's plugins folder: files under it are documents, files elsewhere stay files
     * @param serverScope this server's id on a network, or "" for a server of its own
     */
    public Documents(Database database, NetworkBus bus, Path root, String serverScope, LongSupplier clock) {
        this.database = database;
        this.bus = bus == null ? NetworkBus.NONE : bus;
        this.root = root.toAbsolutePath().normalize();
        this.serverScope = serverScope == null ? "" : serverScope;
        this.clock = clock;
        this.bus.subscribe(TOPIC, event -> Address.ofKey(event.key()).ifPresent(this::changed));
    }

    /** Makes every {@link YamlStore} under {@code root} keep its document here from now on. Core calls it at startup. */
    public static void install(Documents documents) {
        installed = documents;
    }

    public static void uninstall() {
        installed = null;
    }

    public static Optional<Documents> installed() {
        return Optional.ofNullable(installed);
    }

    // ------------------------------------------------------------------------------ addresses

    /** The document a file under the plugins folder is, or empty for a file anywhere else. */
    public Optional<Address> addressOf(Path file, Scope scope) {
        if (file == null) {
            return Optional.empty();
        }
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(root) || absolute.getNameCount() < root.getNameCount() + 2) {
            return Optional.empty();
        }
        Path relative = root.relativize(absolute);
        String owner = relative.getName(0).toString();
        String name = relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/');
        return Optional.of(new Address(scope == Scope.NETWORK ? NETWORK_SCOPE : serverScope, owner, name));
    }

    /** This server's scope: its id on a network, "" on a server of its own. */
    public String serverScope() {
        return serverScope;
    }

    /** The plugin folder documents are named under, for writing an exported copy beside them. */
    public Path root() {
        return root;
    }

    /** Every owner (plugin folder) with a document in {@code scope}. */
    public List<String> owners(String scope) {
        return database.read(connection -> {
            List<String> owners = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT DISTINCT owner FROM document WHERE scope = ? ORDER BY owner")) {
                statement.setString(1, scope);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        owners.add(rows.getString(1));
                    }
                }
            }
            return owners;
        }).orElse(List.of());
    }

    // ------------------------------------------------------------------------------ reading

    public Read read(Address address) {
        if (!database.isUsable()) {
            return new Failed("the database is not available");
        }
        Optional<Read> read = database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT body, version FROM document WHERE scope = ? AND owner = ? AND name = ?")) {
                bind(statement, address);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? new Found(rows.getString(1), rows.getLong(2)) : new Absent();
                }
            }
        });
        return read.orElseGet(() -> new Failed("the document could not be read"));
    }

    /** The names under {@code prefix} (a folder, "claims/") in one owner's documents of this scope. */
    public List<String> list(String scope, String owner, String prefix) {
        String folder = prefix == null ? "" : prefix;
        return database.read(connection -> {
            List<String> names = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT name FROM document WHERE scope = ? AND owner = ? ORDER BY name")) {
                statement.setString(1, scope);
                statement.setString(2, owner);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String name = rows.getString(1);
                        if (name.startsWith(folder)) {
                            names.add(name);
                        }
                    }
                }
            }
            return names;
        }).orElse(List.of());
    }

    // ------------------------------------------------------------------------------ writing

    /** Writes {@code body} if nobody has the document yet. */
    boolean insertIfAbsent(Address address, String body) {
        boolean[] inserted = new boolean[1];
        boolean ran = database.write(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO document (scope, owner, name, body, version, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, 1, ?, ?) ON CONFLICT (scope, owner, name) DO NOTHING""")) {
                bind(statement, address);
                statement.setString(4, body);
                statement.setLong(5, clock.getAsLong());
                statement.setString(6, serverScope);
                inserted[0] = statement.executeUpdate() == 1;
            }
        });
        if (ran && inserted[0]) {
            changedHere(address);
        }
        return ran && inserted[0];
    }

    /** Writes {@code body} if the document is still at {@code version}. */
    boolean compareAndSet(Address address, long version, String body) {
        boolean[] updated = new boolean[1];
        boolean ran = database.write(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE document SET body = ?, version = version + 1, updated_at = ?, updated_by = ?
                    WHERE scope = ? AND owner = ? AND name = ? AND version = ?""")) {
                statement.setString(1, body);
                statement.setLong(2, clock.getAsLong());
                statement.setString(3, serverScope);
                statement.setString(4, address.scope());
                statement.setString(5, address.owner());
                statement.setString(6, address.name());
                statement.setLong(7, version);
                updated[0] = statement.executeUpdate() == 1;
            }
        });
        if (ran && updated[0]) {
            changedHere(address);
        }
        return ran && updated[0];
    }

    boolean delete(Address address) {
        boolean ran = database.write(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM document WHERE scope = ? AND owner = ? AND name = ?")) {
                bind(statement, address);
                statement.executeUpdate();
            }
        });
        if (ran) {
            changedHere(address);
        }
        return ran;
    }

    /** Whether the database can be written at all — a write refused for that is not a conflict to retry. */
    boolean isUsable() {
        return database.isUsable();
    }

    // ------------------------------------------------------------------------------ importing

    /** Remembers that this address has been brought over from its file, once per run. */
    boolean firstTimeFor(Address address) {
        return imported.add(address.key());
    }

    // ------------------------------------------------------------------------------ listening

    /** Runs {@code listener} whenever the document changes — written here by another store, or by another server. */
    NetworkBus.Subscription listen(Address address, Runnable listener) {
        List<Runnable> forAddress = listeners.computeIfAbsent(address.key(), ignored -> new CopyOnWriteArrayList<>());
        forAddress.add(listener);
        return () -> forAddress.remove(listener);
    }

    private void changedHere(Address address) {
        changed(address);
        if (NETWORK_SCOPE.equals(address.scope())) {
            bus.publish(TOPIC, address.key());
        }
    }

    private void changed(Address address) {
        List<Runnable> forAddress = listeners.get(address.key());
        if (forAddress == null) {
            return;
        }
        for (Runnable listener : forAddress) {
            try {
                listener.run();
            } catch (RuntimeException failed) {
                log.error(failed, "A listener for {} failed.", address.key());
            }
        }
    }

    private static void bind(PreparedStatement statement, Address address) throws java.sql.SQLException {
        statement.setString(1, address.scope());
        statement.setString(2, address.owner());
        statement.setString(3, address.name());
    }
}
