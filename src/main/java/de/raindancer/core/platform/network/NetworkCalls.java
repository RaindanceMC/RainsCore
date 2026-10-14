package de.raindancer.core.platform.network;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Asking one server of the network a question and getting its answer — what the 1.x link's requests were,
 * between any two servers. Carried on the {@link NetworkBus}: the question is an event for the server asked,
 * the answer an event back.
 *
 * <p>An answer that does not come within the timeout is an empty answer, never a hang: the caller decides what
 * "nobody answered" means. Handlers run on the bus's thread; one that touches the world hops to the right
 * scheduler and answers from what it already knows.
 */
public final class NetworkCalls implements AutoCloseable {

    private static final LogChannel log = Log.of("network");

    static final String CALL = "call";
    static final String REPLY = "reply";

    private final NetworkBus bus;
    private final Map<String, Function<Map<String, String>, CompletableFuture<Map<String, String>>>> handlers =
            new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Optional<Map<String, String>>>> waiting = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rains-calls-timeouts");
        thread.setDaemon(true);
        return thread;
    });
    private final NetworkBus.Subscription calls;
    private final NetworkBus.Subscription replies;

    public NetworkCalls(NetworkBus bus) {
        this.bus = bus;
        this.calls = bus.subscribe(CALL, this::called);
        this.replies = bus.subscribe(REPLY, this::replied);
    }

    /** Answers {@code topic} for whoever asks this server. Close the subscription to stop. */
    public NetworkBus.Subscription answer(String topic, Function<Map<String, String>, Map<String, String>> handler) {
        return answerLater(topic, question -> CompletableFuture.completedFuture(handler.apply(question)));
    }

    /**
     * Answers {@code topic} when the returned future completes — for an answer that needs the server thread or
     * another server. The bus goes on delivering meanwhile; a future that fails is an empty answer.
     */
    public NetworkBus.Subscription answerLater(String topic,
                                               Function<Map<String, String>, CompletableFuture<Map<String, String>>> handler) {
        handlers.put(topic, handler);
        return () -> handlers.remove(topic, handler);
    }

    /** Asks {@code server} about {@code topic}; empty when it does not answer within {@code timeout}. */
    public CompletableFuture<Optional<Map<String, String>>> ask(String server, String topic, Map<String, String> data,
                                                                Duration timeout) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<Optional<Map<String, String>>> answer = new CompletableFuture<>();
        waiting.put(id, answer);
        timeouts.schedule(() -> {
            CompletableFuture<Optional<Map<String, String>>> unanswered = waiting.remove(id);
            if (unanswered != null) {
                unanswered.complete(Optional.empty());
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        Map<String, String> question = new LinkedHashMap<>();
        question.put("~to", server);
        question.put("~topic", topic);
        question.put("~from", bus.server());
        question.putAll(data);
        if (!bus.publish(CALL, id, encode(question))) {
            waiting.remove(id);
            answer.complete(Optional.empty());
        }
        return answer;
    }

    private void called(NetworkEvent event) {
        Map<String, String> question = decode(event.payload());
        if (!bus.server().equals(question.remove("~to"))) {
            return;
        }
        String topic = question.remove("~topic");
        String from = question.remove("~from");
        Function<Map<String, String>, CompletableFuture<Map<String, String>>> handler =
                topic == null ? null : handlers.get(topic);
        if (handler == null) {
            return;
        }
        CompletableFuture<Map<String, String>> answering;
        try {
            answering = handler.apply(question);
        } catch (RuntimeException failed) {
            answering = CompletableFuture.failedFuture(failed);
        }
        String key = event.key();
        (answering == null ? CompletableFuture.<Map<String, String>>completedFuture(null) : answering)
                .whenComplete((answered, failed) -> {
                    Map<String, String> reply = new LinkedHashMap<>();
                    reply.put("~to", from == null ? "" : from);
                    if (failed != null) {
                        log.error(failed, "Answering '{}' for {} failed.", topic, from);
                        reply.put("~ok", "false");
                    } else {
                        reply.put("~ok", "true");
                        if (answered != null) {
                            reply.putAll(answered);
                        }
                    }
                    bus.publish(REPLY, key, encode(reply));
                });
    }

    private void replied(NetworkEvent event) {
        Map<String, String> reply = decode(event.payload());
        if (!bus.server().equals(reply.remove("~to"))) {
            return;
        }
        CompletableFuture<Optional<Map<String, String>>> answer = waiting.remove(event.key());
        if (answer == null) {
            return;
        }
        boolean ok = "true".equals(reply.remove("~ok"));
        answer.complete(ok ? Optional.of(Map.copyOf(reply)) : Optional.empty());
    }

    static String encode(Map<String, String> data) {
        StringBuilder out = new StringBuilder();
        data.forEach((key, value) -> {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
        });
        return out.toString();
    }

    static Map<String, String> decode(String text) {
        Map<String, String> data = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) {
            return data;
        }
        for (String pair : text.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            data.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return data;
    }

    @Override
    public void close() {
        calls.close();
        replies.close();
        waiting.values().forEach(answer -> answer.complete(Optional.empty()));
        waiting.clear();
        timeouts.shutdownNow();
    }
}
