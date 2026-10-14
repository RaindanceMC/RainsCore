package de.raindancer.core.social.economy;

import de.raindancer.core.data.sql.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * The bank's record of every money movement asked of it, by the request id the asking server chose. A row is
 * written before the money moves and gets its outcome after, so a request is applied at most once — also when
 * the answer was lost, the caller asks again, and the bank restarted in between. A row without an outcome
 * (the bank stopped mid-way) is never applied again: whether it moved is uncertain, and that is what it answers.
 */
public final class BankRequests {

    /** How long a request's answer is kept. Retries come within seconds; a day is far more than enough. */
    static final long KEPT_MILLIS = 24 * 60 * 60_000L;

    private static final int LONGEST_ID = 64;

    /** What {@link #begin} decided. */
    public sealed interface Begun permits GoAhead, Answered, Uncertain {
    }

    /** New: move the money, then {@link #finish}. */
    public record GoAhead() implements Begun {
    }

    /** Asked before and answered: answer the same again. */
    public record Answered(EconomyResult.Outcome outcome, Money balance) implements Begun {
    }

    /** Begun and never finished, or nothing could be recorded: move nothing. */
    public record Uncertain() implements Begun {
    }

    private final Database database;
    private final LongSupplier clock;

    public BankRequests(Database database, LongSupplier clock) {
        this.database = database;
        this.clock = clock;
    }

    public Begun begin(String request) {
        if (request == null || request.isEmpty() || request.length() > LONGEST_ID) {
            return new Uncertain();
        }
        AtomicReference<Begun> begun = new AtomicReference<>(new Uncertain());
        boolean written = database.write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO economy_request (id, outcome, balance, at) VALUES (?, NULL, NULL, ?) ON CONFLICT(id) DO NOTHING")) {
                insert.setString(1, request);
                insert.setLong(2, clock.getAsLong());
                if (insert.executeUpdate() == 1) {
                    begun.set(new GoAhead());
                    return;
                }
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT outcome, balance FROM economy_request WHERE id = ?")) {
                select.setString(1, request);
                try (ResultSet rows = select.executeQuery()) {
                    if (rows.next() && rows.getString(1) != null) {
                        begun.set(new Answered(outcome(rows.getString(1)), Money.of(rows.getLong(2))));
                    }
                }
            }
        });
        return written ? begun.get() : new Uncertain();
    }

    /** Records what a request that went ahead came to. */
    public void finish(String request, EconomyResult.Outcome outcome, Money balance) {
        database.write(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE economy_request SET outcome = ?, balance = ? WHERE id = ?")) {
                update.setString(1, outcome.name());
                update.setLong(2, balance.minor());
                update.setString(3, request);
                update.executeUpdate();
            }
        });
    }

    /** Forgets requests older than {@link #KEPT_MILLIS}; how many. */
    public int prune() {
        long before = clock.getAsLong() - KEPT_MILLIS;
        java.util.concurrent.atomic.AtomicInteger removed = new java.util.concurrent.atomic.AtomicInteger();
        database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM economy_request WHERE at < ?")) {
                delete.setLong(1, before);
                removed.set(delete.executeUpdate());
            }
        });
        return removed.get();
    }

    private static EconomyResult.Outcome outcome(String name) {
        try {
            return EconomyResult.Outcome.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return EconomyResult.Outcome.REFUSED;
        }
    }
}
