package de.raindancer.core.data.sql;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Every database this server keeps, by name.
 *
 * <h2>Why more than one</h2>
 * Because they are not all the same kind of thing, and one file would force the slowest and largest
 * of them onto the rest:
 *
 * <ul>
 *   <li><b>{@code core}</b> — what the server <em>is</em>: warps, homes, custom items, loot tables,
 *       achievements. Small, read constantly, written rarely, and the thing you want a backup of.</li>
 *   <li><b>{@code audit}</b> — what was <em>done</em>: an append-only journal that only grows, is
 *       written far more often than everything else put together, and is thrown away by age. Keeping
 *       it beside the warps would mean every backup of a handful of warps carries a year of history,
 *       and every write of a history line contends with reading them.</li>
 * </ul>
 *
 * <p>They are also thrown away differently, which is the deciding argument: an audit journal has a
 * retention period and the rest does not. One file cannot have two retention policies.
 *
 * <h2>Names are files, or namespaces</h2>
 * On a server of its own a name becomes {@code <name>.db} in the plugin's folder, so what is on disk
 * matches what the code calls it. On a network (2.0.0) the same name is a namespace — a Postgres
 * schema — in the one central database every server of the network shares, reached through this
 * server's single connection pool. Callers ask for a name either way and do not know which it is.
 */
public final class Databases implements AutoCloseable {

    private static final LogChannel log = Log.of("sql");

    /** What the server is: warps, homes, items, loot, achievements. */
    public static final String CORE = "core";

    /** What was done: the audit journal, which only grows and is aged out. */
    public static final String AUDIT = "audit";

    private final Path folder;
    private final PostgresPool pool;
    private final String serverId;
    private final BooleanSupplier onServerThread;
    private final Map<String, Database> open = new ConcurrentHashMap<>();

    /**
     * SQLite files, for a server on its own.
     *
     * @param folder         where the files live, usually the plugin's data folder
     * @param onServerThread how to tell whether the caller is on a thread running the world
     */
    public Databases(Path folder, BooleanSupplier onServerThread) {
        this(folder, null, "", onServerThread);
    }

    private Databases(Path folder, PostgresPool pool, String serverId, BooleanSupplier onServerThread) {
        this.folder = folder;
        this.pool = pool;
        this.serverId = serverId;
        this.onServerThread = onServerThread;
    }

    /**
     * Namespaces in a network's central PostgreSQL. The pool is this server's and is closed with these.
     *
     * @param serverId this server's id on the network, recorded beside every schema step it applies
     */
    public static Databases central(PostgresPool pool, String serverId, BooleanSupplier onServerThread) {
        return new Databases(null, pool, serverId, onServerThread);
    }

    /**
     * As {@link #central(PostgresPool, String, BooleanSupplier)}, bringing in what this server kept before it
     * joined the network: a {@code <name>.db} found in {@code formerFolder} is imported into its namespace the
     * first time that namespace is opened — see {@link SqliteImport} — and kept renamed.
     */
    public static Databases central(PostgresPool pool, String serverId, BooleanSupplier onServerThread,
                                    Path formerFolder) {
        return new Databases(formerFolder, pool, serverId, onServerThread);
    }

    /** Whether these live in the network's central database rather than in files of this server's. */
    public boolean isCentral() {
        return pool != null;
    }

    public Dialect dialect() {
        return isCentral() ? Dialect.POSTGRESQL : Dialect.SQLITE;
    }

    /** The central database's pool, or null on a server of its own. */
    public PostgresPool pool() {
        return pool;
    }

    /**
     * The named database, opened and brought up to date the first time it is asked for.
     *
     * <p>Opened once and shared, because two {@link Database} objects over one file would be two
     * write locks that know nothing about each other — and SQLite's answer to that is to fail one of
     * them under load rather than to queue it.
     *
     * <p>Asking twice with different schemas is a programming mistake and is reported as one: the
     * first schema wins, because it is the one the tables were built from.
     */
    public Database of(String name, Schema schema) {
        // On a network: this server's own namespace, as its own <name>.db file was. A plugin whose stores were
        // written for one server must not find a second server writing the same rows from a stale copy — it
        // asks for shared() once its stores are safe for that.
        return open(name, schema, isCentral() ? name + "_" + NetworkName.of(serverId) : name);
    }

    /**
     * The network's one database of this name — every server reads and writes the same tables. Only for stores
     * built for that: they refresh what other servers change and never write a stale copy back. On a server of
     * its own it is the same file {@link #of} gives.
     */
    public Database shared(String name, Schema schema) {
        return open(name, schema, name);
    }

    private Database open(String name, Schema schema, String namespace) {
        return open.computeIfAbsent(namespace, key -> {
            if (!isCentral()) {
                return Database.open(folder.resolve(name + ".db"), schema, onServerThread);
            }
            PostgresDatabase central = PostgresDatabase.open(pool, key, schema, onServerThread, serverId);
            // What this server kept in its own file before it joined the network comes along, once.
            Path former = folder == null ? null : folder.resolve(name + ".db");
            if (central.isUsable() && former != null && java.nio.file.Files.isRegularFile(former)) {
                SqliteImport.into(central, former, schema, serverId);
            }
            return central;
        });
    }

    /** A server id as part of a namespace: lower case letters, digits and _, short enough for Postgres' 63. */
    private static final class NetworkName {
        static String of(String serverId) {
            String clean = serverId == null ? "" : serverId.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            return clean.length() > 30 ? clean.substring(0, 30) : clean;
        }
    }

    /**
     * The core database, with every table this library keeps in it.
     *
     * <p>One schema for the whole file rather than one per subsystem, which was the first attempt and
     * does not work: the version is a single number per database, so two subsystems each counting
     * their own steps would each think the other's progress was theirs. The second one to be applied
     * would find the version already past its own step count and skip its tables entirely — and it
     * would do so silently, on a fresh install, with the failure appearing later as a missing table.
     *
     * <p>Keeping the whole shape in one ordered list also means there is exactly one place to read to
     * know what is in the file, which is worth more than having each subsystem's tables next to its
     * code.
     */
    public Database core() {
        return shared(CORE, CoreSchema.CORE);
    }

    /** The audit journal, which is its own file because it only grows and is aged out. */
    public Database audit() {
        return shared(AUDIT, CoreSchema.AUDIT);
    }

    /** Whether a database is open and complete. */
    public boolean isUsable(String name) {
        Database database = open.get(name);
        return database != null && database.isUsable();
    }

    public Collection<Database> all() {
        return List.copyOf(open.values());
    }

    /** How often any database has been used from a thread that is running the world. */
    public int workOnServerThread() {
        return open.values().stream().mapToInt(Database::workOnServerThread).sum();
    }

    /**
     * Closes all of them.
     *
     * <p>Each one separately and each one guarded, because a database that will not close must not
     * stop the next one from being closed cleanly — an unclosed database is the one that leaves a
     * write-ahead log behind.
     */
    @Override
    public void close() {
        open.values().forEach(database -> {
            try {
                database.close();
            } catch (RuntimeException failed) {
                log.error(failed, "Could not close {} cleanly.", database.describe());
            }
        });
        open.clear();
        if (pool != null) {
            pool.close();
        }
    }
}
