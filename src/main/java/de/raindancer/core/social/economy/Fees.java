package de.raindancer.core.social.economy;

import java.util.Optional;
import java.util.UUID;

/**
 * Charging and paying for things the same way in every plugin: a teleport fee, claim upkeep, a fine, a
 * server goal's reward.
 *
 * <p>A fee is the owner's written amount, moved with the {@link PriceIndex} when the economy says fees follow
 * prices, then put through the {@link EconomyLevers}. A payout goes through the levers only. Both carry a
 * <em>source</em> — {@code tpa.fee}, {@code claims.upkeep} — so the economy can say where money comes from
 * and where it goes.
 *
 * <p>An amount of zero is done at once without asking anybody: every fee a plugin ships defaults to zero, and
 * a server without an economy must not be refused a teleport for a fee of nothing. A real fee without an
 * economy is refused as {@link EconomyResult.Outcome#UNAVAILABLE}, never waved through for free.
 */
public final class Fees {

    private Fees() {
    }

    /** What {@link #charge} would take for {@code written} now. */
    public static Money quote(String source, Money written) {
        if (written == null || !written.isPositive()) {
            return Money.ZERO;
        }
        return EconomyLevers.sink(source, PriceIndex.follow(written));
    }

    /** What {@link #pay} would pay for {@code base} now. */
    public static Money payout(String source, Money base) {
        if (base == null || !base.isPositive()) {
            return Money.ZERO;
        }
        return EconomyLevers.faucet(source, base);
    }

    /** Takes a fee. The result's amount is what was actually asked for, after index and levers. */
    public static EconomyResult charge(UUID payer, Money written, String reason, String source) {
        Money amount = quote(source, written);
        if (!amount.isPositive()) {
            return EconomyResult.done(Money.ZERO, Money.ZERO);
        }
        Optional<Economy> economy = Economies.current();
        if (economy.isEmpty()) {
            return EconomyResult.failed(EconomyResult.Outcome.UNAVAILABLE, amount, Money.ZERO);
        }
        Economy bank = economy.get();
        bank.createAccount(payer);
        return bank.withdraw(payer, amount, reason, source);
    }

    /** Pays something out — a reward, a goal's share. On a capped server it comes out of the treasury. */
    public static EconomyResult pay(UUID to, Money base, String reason, String source) {
        Money amount = payout(source, base);
        if (!amount.isPositive()) {
            return EconomyResult.done(Money.ZERO, Money.ZERO);
        }
        Optional<Economy> economy = Economies.current();
        if (economy.isEmpty()) {
            return EconomyResult.failed(EconomyResult.Outcome.UNAVAILABLE, amount, Money.ZERO);
        }
        Economy bank = economy.get();
        bank.createAccount(to);
        return bank.deposit(to, amount, reason, source);
    }

    /** Gives back exactly what a {@link #charge} took — no index, no levers. */
    public static EconomyResult refund(UUID to, Money taken, String reason, String source) {
        if (taken == null || !taken.isPositive()) {
            return EconomyResult.done(Money.ZERO, Money.ZERO);
        }
        Optional<Economy> economy = Economies.current();
        if (economy.isEmpty()) {
            return EconomyResult.failed(EconomyResult.Outcome.UNAVAILABLE, taken, Money.ZERO);
        }
        return economy.get().refund(to, taken, reason, source);
    }

    /**
     * An amount as an owner wrote it in a settings file — {@code 12.50}, {@code 1.5k} — read in the server's
     * currency. Blank, negative or unreadable is zero, which every fee treats as "no fee".
     */
    public static Money amount(String written) {
        if (written == null || written.isBlank()) {
            return Money.ZERO;
        }
        return currency().parse(written.strip()).orElse(Money.ZERO);
    }

    /** The server's currency — the economy's, or Core's default when there is none — for reading and writing amounts. */
    public static Currency currency() {
        return Economies.current().map(Economy::currency).orElse(Currency.DEFAULT);
    }

    /** The amount written in the server's currency, or plainly when there is no economy. */
    public static String format(Money amount) {
        return Economies.current().map(bank -> bank.format(amount)).orElseGet(() -> Currency.DEFAULT.format(amount));
    }
}
