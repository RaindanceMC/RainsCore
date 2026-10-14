package de.raindancer.core.data.sql;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Brings a server's SQLite database into the network's PostgreSQL — the step a 1.x server, or a 2.0 server
 * that ran on its own, takes when its {@code network.yml} points at a network.
 *
 * <h2>What happens to the rows</h2>
 * <ul>
 *   <li>The file is brought up to the current schema first, so both sides have the same tables.</li>
 *   <li>A row whose key the network already has is <b>kept as the network has it</b> — another server got
 *       there first — and counted, so the log says how many.</li>
 *   <li>A table keyed by a generated number (an audit entry, a chat line, a seed) gets new numbers here, and
 *       every row pointing at one (an entry's fields) is pointed at its new number. Two servers' entry 1
 *       are two entries.</li>
 *   <li>"This server" in a 1.x row — an empty {@code server} column, an empty document {@code scope} — becomes
 *       this server's id, because on a network a world or a config is some server's.</li>
 * </ul>
 * Everything goes in one transaction: a failure leaves the network's database as it was and the file where
 * it is, to be tried again at the next start. On success the file is kept, renamed {@code *.imported-2.0.0}.
 */
public final class SqliteImport {

    private static final LogChannel log = Log.of("sql");
    private static final String IMPORTED = ".imported-2.0.0";

    /** Per table: rows copied, rows the network already had (kept as it had them). */
    public record Report(Map<String, Integer> copied, Map<String, Integer> kept, boolean failed) {
        static Report failure() {
            return new Report(Map.of(), Map.of(), true);
        }

        public int total() {
            return copied.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private record Column(String name, boolean key) {
    }

    private record Table(String name, List<Column> columns, String rekeyed, Map<String, String> references) {
    }

    private SqliteImport() {
    }

    /** Imports {@code file} into {@code central}, whose schema is {@code schema}, as server {@code serverId}. */
    public static Report into(PostgresDatabase central, Path file, Schema schema, String serverId) {
        if (file == null || !Files.isRegularFile(file) || !central.isUsable()) {
            return Report.failure();
        }
        SqliteDatabase old = SqliteDatabase.open(file, schema, () -> false);
        Report report;
        try {
            if (!old.isUsable()) {
                log.error("{} could not be opened, so it was not brought into the network's database. It is left "
                        + "where it is.", file);
                return Report.failure();
            }
            Optional<List<Table>> tables = old.read(SqliteImport::tables);
            if (tables.isEmpty()) {
                return Report.failure();
            }
            report = copy(old, central, tables.get(), serverId);
        } finally {
            old.close();
        }
        if (report.failed()) {
            log.error("{} could not be brought into the network's database; nothing was changed there, and the file "
                    + "is tried again at the next start.", file);
            return report;
        }
        retire(file);
        log.info("{} is in the network's database now ({} rows; {} the network already had were kept as it had them).",
                file.getFileName(), report.total(), report.kept().values().stream().mapToInt(Integer::intValue).sum());
        return report;
    }

    // ------------------------------------------------------------------------------ reading the file

    private static List<Table> tables(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY rowid")) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        List<Table> tables = new ArrayList<>();
        for (String name : names) {
            List<Column> columns = new ArrayList<>();
            String integerKey = null;
            int keys = 0;
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("PRAGMA table_info(\"" + name + "\")")) {
                while (rows.next()) {
                    boolean key = rows.getInt("pk") > 0;
                    columns.add(new Column(rows.getString("name"), key));
                    if (key) {
                        keys++;
                        if ("INTEGER".equalsIgnoreCase(rows.getString("type"))) {
                            integerKey = rows.getString("name");
                        }
                    }
                }
            }
            Map<String, String> references = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("PRAGMA foreign_key_list(\"" + name + "\")")) {
                while (rows.next()) {
                    references.put(rows.getString("from"), rows.getString("table"));
                }
            }
            // A single INTEGER key is SQLite's rowid: a generated number, given anew on the network.
            tables.add(new Table(name, columns, keys == 1 ? integerKey : null, references));
        }
        return tables;
    }

    // ------------------------------------------------------------------------------ copying

    private static Report copy(SqliteDatabase old, PostgresDatabase central, List<Table> tables, String serverId) {
        Map<String, Integer> copied = new LinkedHashMap<>();
        Map<String, Integer> kept = new LinkedHashMap<>();
        Map<String, Map<Long, Long>> newIds = new HashMap<>();
        boolean written = central.write(target -> {
            for (Table table : tables) {
                Map<String, Integer> types = targetTypes(target, table.name());
                if (types.isEmpty()) {
                    log.warn("The network's database has no table '{}'; its rows were not brought over.", table.name());
                    continue;
                }
                List<Column> written2 = table.columns().stream()
                        .filter(column -> !column.name().equals(table.rekeyed()))
                        .filter(column -> types.containsKey(column.name().toLowerCase(Locale.ROOT)))
                        .toList();
                String names = String.join(", ", written2.stream().map(Column::name).toList());
                String marks = String.join(", ", written2.stream().map(column -> "?").toList());
                String sql = "INSERT INTO " + table.name() + " (" + names + ") VALUES (" + marks + ")"
                        + (table.rekeyed() != null ? " RETURNING " + table.rekeyed() : " ON CONFLICT DO NOTHING");
                Map<Long, Long> ids = new HashMap<>();
                int[] counts = new int[2];
                List<Map<String, Object>> rows = old.read(source -> readRows(source, table)).orElseThrow(
                        () -> new SQLException("the rows of " + table.name() + " could not be read"));
                try (PreparedStatement insert = target.prepareStatement(sql)) {
                    for (Map<String, Object> row : rows) {
                        if (!pointAtNewIds(row, table, newIds)) {
                            counts[1]++;
                            continue;
                        }
                        stampServer(row, table.name(), serverId);
                        for (int at = 0; at < written2.size(); at++) {
                            Column column = written2.get(at);
                            bind(insert, at + 1, row.get(column.name()), types.get(column.name().toLowerCase(Locale.ROOT)));
                        }
                        if (table.rekeyed() != null) {
                            try (ResultSet generated = insert.executeQuery()) {
                                generated.next();
                                Object oldId = row.get(table.rekeyed());
                                if (oldId != null) {
                                    ids.put(((Number) oldId).longValue(), generated.getLong(1));
                                }
                            }
                            counts[0]++;
                        } else if (insert.executeUpdate() == 1) {
                            counts[0]++;
                        } else {
                            counts[1]++;
                        }
                    }
                }
                if (table.rekeyed() != null) {
                    newIds.put(table.name(), ids);
                }
                copied.put(table.name(), counts[0]);
                if (counts[1] > 0) {
                    kept.put(table.name(), counts[1]);
                }
            }
        });
        return written ? new Report(Map.copyOf(copied), Map.copyOf(kept), false) : Report.failure();
    }

    private static List<Map<String, Object>> readRows(Connection source, Table table) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement statement = source.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM \"" + table.name() + "\"")) {
            while (result.next()) {
                Map<String, Object> row = new HashMap<>();
                for (Column column : table.columns()) {
                    row.put(column.name(), result.getObject(column.name()));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** Rewrites references to re-keyed rows; false when the row points at one that never came over. */
    private static boolean pointAtNewIds(Map<String, Object> row, Table table, Map<String, Map<Long, Long>> newIds) {
        for (Map.Entry<String, String> reference : table.references().entrySet()) {
            Map<Long, Long> ids = newIds.get(reference.getValue());
            Object value = row.get(reference.getKey());
            if (ids == null || value == null) {
                continue;
            }
            Long now = ids.get(((Number) value).longValue());
            if (now == null) {
                return false;
            }
            row.put(reference.getKey(), now);
        }
        return true;
    }

    /** "This server" in a 1.x row is this server's id on the network. */
    private static void stampServer(Map<String, Object> row, String table, String serverId) {
        if ("".equals(row.get("server"))) {
            row.put("server", serverId);
        }
        if ("document".equals(table) && "".equals(row.get("scope"))) {
            row.put("scope", serverId);
        }
    }

    private static Map<String, Integer> targetTypes(Connection target, String table) throws SQLException {
        Map<String, Integer> types = new HashMap<>();
        try (PreparedStatement select = target.prepareStatement("""
                SELECT column_name, data_type FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ?""")) {
            select.setString(1, table.toLowerCase(Locale.ROOT));
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    types.put(rows.getString(1), switch (rows.getString(2)) {
                        case "bigint", "integer", "smallint" -> Types.BIGINT;
                        case "double precision", "real", "numeric" -> Types.DOUBLE;
                        case "bytea" -> Types.BINARY;
                        case "boolean" -> Types.BOOLEAN;
                        default -> Types.VARCHAR;
                    });
                }
            }
        }
        return types;
    }

    /** SQLite keeps whatever it was given; Postgres wants each column's own type. */
    private static void bind(PreparedStatement insert, int at, Object value, int type) throws SQLException {
        if (value == null) {
            insert.setNull(at, type);
            return;
        }
        switch (type) {
            case Types.BIGINT -> insert.setLong(at, value instanceof Number number ? number.longValue() : Long.parseLong(value.toString().trim()));
            case Types.DOUBLE -> insert.setDouble(at, value instanceof Number number ? number.doubleValue() : Double.parseDouble(value.toString().trim()));
            case Types.BINARY -> insert.setBytes(at, value instanceof byte[] bytes ? bytes : value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            case Types.BOOLEAN -> insert.setBoolean(at, value instanceof Number number ? number.intValue() != 0 : Boolean.parseBoolean(value.toString()));
            default -> insert.setString(at, value instanceof byte[] bytes
                    ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : value.toString());
        }
    }

    private static void retire(Path file) {
        Path kept = file.resolveSibling(file.getFileName() + IMPORTED);
        if (Files.exists(kept)) {
            kept = file.resolveSibling(file.getFileName() + IMPORTED + "-" + System.currentTimeMillis());
        }
        try {
            Files.move(file, kept);
            for (String suffix : List.of("-wal", "-shm")) {
                Path beside = file.resolveSibling(file.getFileName() + suffix);
                if (Files.exists(beside)) {
                    Files.move(beside, kept.resolveSibling(kept.getFileName() + suffix));
                }
            }
        } catch (IOException failure) {
            // Left in place it would be brought over again next start: rows the network has are kept, generated
            // ones would come twice. Loud.
            log.error("{} is in the network's database but could not be renamed ({}). Move it away before the next "
                    + "start, or its numbered rows are brought over a second time.", file, failure.getMessage());
        }
    }
}
