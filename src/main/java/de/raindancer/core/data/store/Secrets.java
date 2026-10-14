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
        if (database == null || !database.isUsable()) {
            return Optional.empty();
        }
        byte[] offered = fromFile(oldFile, length).orElseGet(() -> {
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

    private static Optional<byte[]> fromFile(Path file, int length) {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            byte[] kept = Files.readAllBytes(file);
            return kept.length >= Math.min(16, length) ? Optional.of(kept) : Optional.empty();
        } catch (IOException unreadable) {
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
                log.warn("{} was not used: the network already had its own. Things made with this server's old key "
                        + "(blocked-join hashes, banknotes) will not match it. The file is kept as {}.",
                        file.getFileName(), kept.getFileName());
            }
        } catch (IOException failure) {
            log.warn("{} could not be renamed ({}).", file, failure.getMessage());
        }
    }
}
