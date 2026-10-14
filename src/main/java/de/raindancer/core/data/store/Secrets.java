package de.raindancer.core.data.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Secrets every server of a network has to share: the key blocked-join addresses are hashed with (so a
 * hash recorded on one server matches on another), the seal on banknotes (so a note printed on one server
 * is good on another). Kept in the network's database, never in a file.
 *
 * <p>A secret is made by whichever server needs it first; the others take that one. Two servers asking at
 * the same moment cannot end up with two: the second insert finds the first and reads it.
 */
public final class Secrets {

    private static final LogChannel log = Log.of("store");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Database database;
    private final LongSupplier clock;

    public Secrets(Database database, LongSupplier clock) {
        this.database = database;
        this.clock = clock;
    }

    /**
     * The secret called {@code name}, made now with {@code length} random bytes if the network has none —
     * or taken from {@code oldFile}, a 1.x key file, which is then kept renamed {@code *.imported-2.0.0}.
     *
     * @return empty when the database cannot be reached; the caller decides what to do without it
     */
    public Optional<byte[]> bytes(String name, int length, Path oldFile) {
        return bytes(name, length, oldFile, RAW);
    }

    /** The file's bytes are the key — as most 1.x key files were. */
    public static final java.util.function.Function<byte[], byte[]> RAW = file -> file;

    /** The file holds the key as base64 text — as the economy's {@code cash.key} did. */
    public static final java.util.function.Function<byte[], byte[]> BASE64 =
            file -> Base64.getDecoder().decode(new String(file, java.nio.charset.StandardCharsets.US_ASCII).strip());

    /**
     * As {@link #bytes(String, int, Path)}, for an old file that keeps its key in some other form: {@code decode}
     * turns what the file holds into the key. A file that does not decode is not imported.
     */
    public Optional<byte[]> bytes(String name, int length, Path oldFile,
                                  java.util.function.Function<byte[], byte[]> decode) {
        if (database == null || !database.isUsable()) {
            return Optional.empty();
        }
        byte[] offered = fromFile(oldFile, length, decode).orElseGet(() -> {
            byte[] fresh = new byte[length];
            RANDOM.nextBytes(fresh);
            return fresh;
        });
        boolean written = database.write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO secret (name, value, created_at) VALUES (?, ?, ?) ON CONFLICT(name) DO NOTHING")) {
                insert.setString(1, name);
                insert.setString(2, Base64.getEncoder().encodeToString(offered));
                insert.setLong(3, clock.getAsLong());
                insert.executeUpdate();
            }
        });
        if (!written) {
            return Optional.empty();
        }
        Optional<byte[]> kept = database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("SELECT value FROM secret WHERE name = ?")) {
                select.setString(1, name);
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? Base64.getDecoder().decode(rows.getString(1)) : null;
                }
            }
        });
        if (kept.isPresent() && oldFile != null && Files.isRegularFile(oldFile)) {
            retire(oldFile, java.util.Arrays.equals(kept.get(), offered));
        }
        return kept;
    }

    /**
     * A secret somebody gave — a login, a bot token — rather than one made here: empty when there is none (or the
     * database cannot be reached). Never in a document, so {@code /documents} cannot show or export it.
     */
    public Optional<String> text(String name) {
        if (database == null || !database.isUsable()) {
            return Optional.empty();
        }
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("SELECT value FROM secret WHERE name = ?")) {
                select.setString(1, name);
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? new String(Base64.getDecoder().decode(rows.getString(1)),
                            java.nio.charset.StandardCharsets.UTF_8) : null;
                }
            }
        });
    }

    /** Stores (or replaces) a secret somebody gave. @return whether it was written */
    public boolean putText(String name, String value) {
        if (database == null || !database.isUsable() || value == null) {
            return false;
        }
        String encoded = Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO secret (name, value, created_at) VALUES (?, ?, ?)
                    ON CONFLICT(name) DO UPDATE SET value = excluded.value, created_at = excluded.created_at""")) {
                upsert.setString(1, name);
                upsert.setString(2, encoded);
                upsert.setLong(3, clock.getAsLong());
                upsert.executeUpdate();
            }
        });
    }

    /** Forgets a secret somebody gave. @return whether the database could be written */
    public boolean remove(String name) {
        if (database == null || !database.isUsable()) {
            return false;
        }
        return database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM secret WHERE name = ?")) {
                delete.setString(1, name);
                delete.executeUpdate();
            }
        });
    }

    private static Optional<byte[]> fromFile(Path file, int length, java.util.function.Function<byte[], byte[]> decode) {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            byte[] kept = decode.apply(Files.readAllBytes(file));
            return kept != null && kept.length >= Math.min(16, length) ? Optional.of(kept) : Optional.empty();
        } catch (IOException | RuntimeException unreadable) {
            log.warn("{} could not be read ({}); a new secret is made instead.", file, unreadable.getMessage());
            return Optional.empty();
        }
    }

    private static void retire(Path file, boolean taken) {
        Path kept = file.resolveSibling(file.getFileName() + ".imported-2.0.0");
        try {
            Files.move(file, kept);
            if (taken) {
                log.info("{} is the network's secret now and is kept in the database; the file is {}.",
                        file.getFileName(), kept.getFileName());
            } else {
                // Another server's key got there first. Hashes and seals made with this file's key stop matching.
                log.info("{} was not used: the network already had its own. Things made with this server's old key "
                        + "(blocked-join hashes, banknotes) will not match it. The file is kept as {}.",
                        file.getFileName(), kept.getFileName());
            }
        } catch (IOException failure) {
            log.warn("{} could not be renamed ({}).", file, failure.getMessage());
        }
    }
}
