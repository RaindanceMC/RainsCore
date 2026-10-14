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

    /** How long the bank remembers a movement's answer by its request id, so a repeat is never applied twice. */
    static final long REMEMBERED_MILLIS = 10 * 60_000L;

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
     * Answers the network's money questions from {@code local} — this server's own economy, when it has one.
     * Only the server holding {@value #ROLE} is ever asked.
     */
    public static NetworkBus.Subscription serve(NetworkCalls calls, Supplier<Optional<Economy>> local) {
        return serve(calls, local, () -> true, System::currentTimeMillis);
    }

    /**
     * As {@link #serve(NetworkCalls, Supplier)}, answering only while {@code isBank} — a server that lost the role a
     * moment ago must not keep moving money for callers that looked it up before.
     */
    public static NetworkBus.Subscription serve(NetworkCalls calls, Supplier<Optional<Economy>> local,
                                                java.util.function.BooleanSupplier isBank, java.util.function.LongSupplier clock) {
        Map<String, Remembered> answered = new java.util.concurrent.ConcurrentHashMap<>();
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
                return Map.of("outcome", EconomyResult.Outcome.UNAVAILABLE.name());
            }
            String request = data.getOrDefault("request", "");
            long now = clock.getAsLong();
            answered.values().removeIf(old -> now - old.at() > REMEMBERED_MILLIS);
            if (!request.isEmpty()) {
                Remembered before = answered.get(request);
                if (before != null) {
                    return before.answer();
                }
            }
            Economy money = found.get();
            try {
                UUID player = UUID.fromString(data.get("player"));
                Money amount = Money.of(Long.parseLong(data.get("amount")));
                String reason = data.getOrDefault("reason", "");
                EconomyResult result = switch (data.getOrDefault("kind", "")) {
                    case "deposit" -> money.deposit(player, amount, reason, "network");
                    case "withdraw" -> money.withdraw(player, amount, reason, "network");
                    case "transfer" -> money.transfer(player, UUID.fromString(data.get("to")), amount, reason);
                    default -> EconomyResult.failed(EconomyResult.Outcome.REFUSED, amount, Money.ZERO);
                };
                Map<String, String> answer = Map.of("outcome", result.outcome().name(),
                        "balance", String.valueOf(result.balance().minor()));
                if (!request.isEmpty()) {
                    answered.put(request, new Remembered(answer, now));
                }
                return answer;
            } catch (RuntimeException garbled) {
                return Map.of("outcome", EconomyResult.Outcome.REFUSED.name());
            }
        });
        return () -> {
            currency.close();
            balance.close();
            move.close();
        };
    }

    /** A movement's answer, kept by its request id. */
    private record Remembered(Map<String, String> answer, long at) {
    }
}
