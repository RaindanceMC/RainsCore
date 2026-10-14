package de.raindancer.core.social.economy;

import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.NetworkRoles;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The network's money, on a server that does not keep it: every balance and every movement is asked of the
 * server holding the {@value #ROLE} role — the one running the economy — over {@link NetworkCalls}. What the
 * 1.x {@link LinkedEconomy} was for two servers, for any number.
 *
 * <p>Nothing moves when the bank cannot be reached: a deposit or withdrawal answers
 * {@link EconomyResult.Outcome#UNAVAILABLE} rather than guessing.
 */
public final class NetworkEconomy implements Economy {

    /** The role the server keeping the network's money holds. */
    public static final String ROLE = "economy";

    private static final Duration WAIT = Duration.ofSeconds(3);

    /** How often a money movement is asked again, with the same request id, before it counts as unanswered. */
    static final int TRIES = 3;

    private final NetworkCalls calls;
    private final NetworkRoles roles;
    private volatile Currency currency = Currency.DEFAULT;
    private volatile boolean currencyKnown;

    public NetworkEconomy(NetworkCalls calls, NetworkRoles roles) {
        this.calls = calls;
        this.roles = roles;
    }

    @Override
    public String name() {
        return "the network's economy";
    }

    @Override
    public Currency currency() {
        if (!currencyKnown) {
            ask(LinkedEconomy.CURRENCY, Map.of()).filter(answer -> answer.containsKey("singular")).ifPresent(answer -> {
                currency = LinkedEconomy.decode(answer);
                currencyKnown = true;
            });
        }
        return currency;
    }

    @Override
    public boolean hasAccount(UUID player) {
        return ask(LinkedEconomy.BALANCE, Map.of("player", player.toString()))
                .map(answer -> "true".equals(answer.get("account"))).orElse(false);
    }

    @Override
    public Money balance(UUID player) {
        return ask(LinkedEconomy.BALANCE, Map.of("player", player.toString()))
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
        // One id for every try: the bank applies a movement once and answers a repeat with what it answered.
        // Without it a lost answer and a retry pay or charge twice.
        data.put("request", UUID.randomUUID().toString());
        data.put("kind", kind);
        data.put("player", player.toString());
        data.put("amount", String.valueOf(amount.minor()));
        data.put("reason", reason == null ? "" : reason);
        if (to != null) {
            data.put("to", to.toString());
        }
        Optional<Map<String, String>> answered = Optional.empty();
        for (int attempt = 0; attempt < TRIES && answered.isEmpty(); attempt++) {
            answered = ask(LinkedEconomy.MOVE, data);
        }
        return answered.map(answer -> {
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
        Optional<String> bank = roles.holder(ROLE);
        if (bank.isEmpty()) {
            return Optional.empty();
        }
        try {
            return calls.ask(bank.get(), topic, data, WAIT).get(WAIT.toMillis() + 1_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception unreachable) {
            return Optional.empty();
        }
    }

    /**
     * Answers the network's money questions from {@code local} — this server's own economy, when it has one —
     * while {@code isBank}: a server that lost the role a moment ago must not keep moving money for callers that
     * looked it up before. Every movement goes through {@code requests}, so none is applied twice.
     */
    public static NetworkBus.Subscription serve(NetworkCalls calls, Supplier<Optional<Economy>> local,
                                                BooleanSupplier isBank, BankRequests requests) {
        NetworkBus.Subscription currency = calls.answer(LinkedEconomy.CURRENCY,
                data -> local.get().map(money -> LinkedEconomy.encode(money.currency())).orElse(Map.of()));
        NetworkBus.Subscription balance = calls.answer(LinkedEconomy.BALANCE, data -> local.get()
                .filter(money -> isBank.getAsBoolean()).map(money -> {
            UUID player = UUID.fromString(data.get("player"));
            return Map.of("balance", String.valueOf(money.balance(player).minor()),
                    "account", String.valueOf(money.hasAccount(player)));
        }).orElse(Map.of()));
        NetworkBus.Subscription move = calls.answer(LinkedEconomy.MOVE, data -> {
            Optional<Economy> found = local.get();
            if (found.isEmpty() || !isBank.getAsBoolean()) {
                return unavailable();
            }
            UUID player;
            UUID to;
            Money amount;
            try {
                player = UUID.fromString(data.get("player"));
                to = data.containsKey("to") ? UUID.fromString(data.get("to")) : null;
                amount = Money.of(Long.parseLong(data.get("amount")));
            } catch (RuntimeException garbled) {
                return Map.of("outcome", EconomyResult.Outcome.REFUSED.name());
            }
            String request = data.getOrDefault("request", "");
            switch (requests.begin(request)) {
                case BankRequests.Answered before -> {
                    return answer(before.outcome(), before.balance());
                }
                case BankRequests.Uncertain uncertain -> {
                    return unavailable();
                }
                case BankRequests.GoAhead ahead -> {
                }
            }
            Economy money = found.get();
            String reason = data.getOrDefault("reason", "");
            // A throw from here leaves the request unfinished: whether money moved is not known, so it is never
            // tried again.
            EconomyResult result = switch (data.getOrDefault("kind", "")) {
                case "deposit" -> money.deposit(player, amount, reason, "network");
                case "withdraw" -> money.withdraw(player, amount, reason, "network");
                case "transfer" -> to == null
                        ? EconomyResult.failed(EconomyResult.Outcome.REFUSED, amount, Money.ZERO)
                        : money.transfer(player, to, amount, reason);
                default -> EconomyResult.failed(EconomyResult.Outcome.REFUSED, amount, Money.ZERO);
            };
            requests.finish(request, result.outcome(), result.balance());
            return answer(result.outcome(), result.balance());
        });
        return () -> {
            currency.close();
            balance.close();
            move.close();
        };
    }

    private static Map<String, String> answer(EconomyResult.Outcome outcome, Money balance) {
        return Map.of("outcome", outcome.name(), "balance", String.valueOf(balance.minor()));
    }

    private static Map<String, String> unavailable() {
        return Map.of("outcome", EconomyResult.Outcome.UNAVAILABLE.name());
    }
}
