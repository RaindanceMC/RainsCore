package de.raindancer.core.data.sql;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Written after every shutdown logged "Could not fold the write-ahead log … back in" with a SQLITE_LOCKED stack trace:
 * a statement still open on the writer makes the checkpoint answer LOCKED — and closing the connection folds the log
 * in anyway, so there was nothing to warn about.
 */
class DatabaseCloseQuietlyTest {

    @TempDir
    Path folder;

    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        Log.shutdown();
    }

    private void capture() {
        Logger console = Logger.getAnonymousLogger();
        console.setUseParentHandlers(false);
        console.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        Log.configure(folder.resolve("logs"), console, LogLevel.DEBUG, LogLevel.DEBUG, 1);
    }

    @Test
    @DisplayName("a statement left open on the writer: no stack trace at close, and no -wal file left behind")
    void lockedCheckpointIsNotAProblem() throws Exception {
        capture();
        Database database = Database.open(folder.resolve("test.db"), Schema.of(
                "CREATE TABLE thing (id INTEGER PRIMARY KEY, name TEXT NOT NULL)"), () -> false);
        for (int at = 0; at < 20; at++) {
            int row = at;
            database.write(connection -> {
                try (var insert = connection.prepareStatement("INSERT INTO thing (name) VALUES (?)")) {
                    insert.setString(1, "row " + row);
                    insert.executeUpdate();
                }
            });
        }
        // The leak the real servers have somewhere: a result set nobody closed, on the writer.
        database.write(connection -> {
            var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT * FROM thing");
            rows.next();
        });
        database.close();

        assertThat(logged).as("nothing logged with a stack trace").noneMatch(record -> record.getThrown() != null);
        assertThat(logged).noneMatch(record -> record.getMessage() != null && record.getMessage().contains("Could not fold"));
        Path wal = folder.resolve("test.db-wal");
        assertThat(!Files.exists(wal) || Files.size(wal) == 0).as("the log is folded in by the close itself").isTrue();
    }
}
