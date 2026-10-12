package de.raindancer.core.social.economy;

import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.core.ui.text.NameStyle;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The money of the other server of the link, used here: on a server that follows ({@code follows: true} in link.yml)
 * and has no economy of its own. Every balance is asked for and every movement made where the accounts are kept, so
 * there is one balance across both servers — never a copy that could drift or be spent twice.
 *
 * <p>The calls wait for the answer, as {@link Economy}'s contract has it; over the link that is milliseconds. When the
 * other side does not answer, a movement is {@link EconomyResult.Outcome#UNAVAILABLE} and a balance reads as zero —
 * nothing is ever assumed to have happened.
 */
public final class LinkedEconomy implements Economy {

    public static final String BALANCE = "economy.balance";
    public static final String MOVE = "economy.move";
    public static final String CURRENCY = "economy.currency";
    private static final Duration WAIT = Duration.ofSeconds(3);

    private final ServerLink link;
    private volatile Currency currency = Currency.DEFAULT;
    private volatile boolean currencyKnown;

    public LinkedEconomy(ServerLink link) {
        this.link = link;
    }

    /** The other side's currency, so amounts read the same here. */
    public CompletableFuture<Void> refreshCurrency() {
        return link.request(CURRENCY, Map.of(), WAIT).thenAccept(answer -> {
            if (answer.containsKey("singular")) {
                currency = decode(answer);
                currencyKnown = true;
            }
        });
    }

    @Override
    public String name() {
        return "the linked server's economy";
    }

    /**
     * The other side's currency — asked for until it has answered once, since this server may well start before the
     * link is up, and an amount in the wrong currency is a wrong amount.
     */
    @Override
    public Currency currency() {
        if (!currencyKnown) {
            try {
                refreshCurrency().get(WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception unreachable) {
                // Still not known; the default stands until the next try.
            }
        }
        return currency;
    }

    @Override
    public boolean hasAccount(UUID player) {
        return ask(BALANCE, Map.of("player", player.toString())).map(answer -> "true".equals(answer.get("account")))
                .orElse(false);
    }

    @Override
    public Money balance(UUID player) {
        return ask(BALANCE, Map.of("player", player.toString()))
                .map(answer -> Money.of(Long.parseLong(answer.getOrDefault("balance", "0")))).orElse(Money.ZERO);
    }

    @Override
    public EconomyResult deposit(UUID player, Money amount, String reason) {
        return move("deposit", player, null, amount, reason);
    }

    @Override
    public EconomyResult withdraw(UUID player, Money amount, String reason) {
        return move("withdraw", player, null, amount, reason);
    }

    @Override
    public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
        return move("transfer", from, to, amount, reason);
    }

    private EconomyResult move(String kind, UUID player, UUID to, Money amount, String reason) {
        Map<String, String> data = new HashMap<>();
        data.put("kind", kind);
        data.put("player", player.toString());
        data.put("amount", String.valueOf(amount.minor()));
        data.put("reason", reason == null ? "" : reason);
        if (to != null) {
            data.put("to", to.toString());
        }
        return ask(MOVE, data).map(answer -> {
            EconomyResult.Outcome outcome;
            try {
                outcome = EconomyResult.Outcome.valueOf(answer.getOrDefault("outcome", "UNAVAILABLE"));
            } catch (IllegalArgumentException unknown) {
                outcome = EconomyResult.Outcome.REFUSED;
            }
            return new EconomyResult(outcome, amount, Money.of(Long.parseLong(answer.getOrDefault("balance", "0"))));
        }).orElse(EconomyResult.failed(EconomyResult.Outcome.UNAVAILABLE, amount, Money.ZERO));
    }

    private Optional<Map<String, String>> ask(String topic, Map<String, String> data) {
        try {
            return Optional.of(link.request(topic, data, WAIT).get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception unreachable) {
            return Optional.empty();
        }
    }

    /** On the server that keeps the money: answers the follower's balances and movements with its own economy. */
    public static void serve(ServerLink link, Supplier<Optional<Economy>> economy) {
        link.on(CURRENCY, data -> economy.get().map(money -> encode(money.currency())).orElse(Map.of()));
        link.on(BALANCE, data -> economy.get().map(money -> {
            UUID player = UUID.fromString(data.get("player"));
            return Map.of("balance", String.valueOf(money.balance(player).minor()),
                    "account", String.valueOf(money.hasAccount(player)));
        }).orElse(Map.of()));
        link.on(MOVE, data -> {
            Optional<Economy> found = economy.get();
            if (found.isEmpty()) {
                return Map.of("outcome", EconomyResult.Outcome.UNAVAILABLE.name());
            }
            Economy money = found.get();
            try {
                UUID player = UUID.fromString(data.get("player"));
                Money amount = Money.of(Long.parseLong(data.get("amount")));
                String reason = data.getOrDefault("reason", "");
                EconomyResult result = switch (data.getOrDefault("kind", "")) {
                    case "deposit" -> money.deposit(player, amount, reason, "link");
                    case "withdraw" -> money.withdraw(player, amount, reason, "link");
                    case "transfer" -> money.transfer(player, UUID.fromString(data.get("to")), amount, reason);
                    default -> EconomyResult.failed(EconomyResult.Outcome.REFUSED, amount, Money.ZERO);
                };
                return Map.of("outcome", result.outcome().name(), "balance", String.valueOf(result.balance().minor()));
            } catch (RuntimeException garbled) {
                return Map.of("outcome", EconomyResult.Outcome.REFUSED.name());
            }
        });
    }

    public static void stopServing(ServerLink link) {
        link.off(CURRENCY);
        link.off(BALANCE);
        link.off(MOVE);
    }

    static Map<String, String> encode(Currency c) {
        Map<String, String> out = new HashMap<>();
        out.put("singular", c.singular());
        out.put("plural", c.plural());
        out.put("symbol", c.symbol());
        out.put("placement", c.placement().name());
        out.put("decimals", String.valueOf(c.decimals()));
        out.put("group", String.valueOf(c.groupSeparator()));
        out.put("decimal", String.valueOf(c.decimalSeparator()));
        out.put("trim", String.valueOf(c.trimZeros()));
        out.put("nameStyle", c.nameStyle().encode());
        out.put("symbolStyle", c.symbolStyle().encode());
        out.put("amountStyle", c.amountStyle().encode());
        return out;
    }

    static Currency decode(Map<String, String> in) {
        return new Currency(in.get("singular"), in.get("plural"), in.get("symbol"),
                Currency.Placement.valueOf(in.getOrDefault("placement", "BEFORE")),
                Integer.parseInt(in.getOrDefault("decimals", "2")), first(in.get("group"), ','),
                first(in.get("decimal"), '.'), Boolean.parseBoolean(in.get("trim")),
                NameStyle.parse(in.getOrDefault("nameStyle", "")), NameStyle.parse(in.getOrDefault("symbolStyle", "")),
                NameStyle.parse(in.getOrDefault("amountStyle", "")));
    }

    private static char first(String text, char otherwise) {
        return text == null || text.isEmpty() ? otherwise : text.charAt(0);
    }
}
