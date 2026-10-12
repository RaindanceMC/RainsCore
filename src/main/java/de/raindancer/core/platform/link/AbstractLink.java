package de.raindancer.core.platform.link;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** What both ends share: handlers by topic, requests waiting for an answer, and the numbering of messages. */
abstract class AbstractLink implements ServerLink {

    protected static final LogChannel log = Log.of("link");

    private final Map<String, Function<Map<String, String>, Map<String, String>>> handlers = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<Map<String, String>>> waiting = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public void on(String topic, Function<Map<String, String>, Map<String, String>> handler) {
        handlers.put(topic, handler);
    }

    @Override
    public void off(String topic) {
        handlers.remove(topic);
    }

    protected long nextId() {
        return ids.incrementAndGet();
    }

    protected CompletableFuture<Map<String, String>> expect(long id, Duration timeout) {
        CompletableFuture<Map<String, String>> answer = new CompletableFuture<>();
        waiting.put(id, answer);
        answer.orTimeout(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS)
                .whenComplete((value, failure) -> waiting.remove(id));
        return answer;
    }

    protected void fail(long id, Throwable why) {
        CompletableFuture<Map<String, String>> answer = waiting.remove(id);
        if (answer != null) {
            answer.completeExceptionally(why);
        }
    }

    /**
     * Handles one message that came in.
     *
     * @return the answer to send back, or null when none is due
     */
    protected LinkMessage receive(LinkMessage message) {
        if (message.isReply()) {
            CompletableFuture<Map<String, String>> answer = waiting.remove(message.replyTo());
            if (answer != null) {
                answer.complete(message.data());
            }
            return null;
        }
        Map<String, String> reply = null;
        Function<Map<String, String>, Map<String, String>> handler = handlers.get(message.topic());
        if (handler != null) {
            try {
                reply = handler.apply(message.data());
            } catch (RuntimeException failure) {
                log.error(failure, "The handler for link topic '{}' failed.", message.topic());
            }
        }
        if (!message.ask()) {
            return null;
        }
        return new LinkMessage(nextId(), message.topic(), reply == null ? Map.of() : reply, message.id(), false);
    }

    protected void failAll(Throwable why) {
        waiting.values().forEach(answer -> answer.completeExceptionally(why));
        waiting.clear();
    }
}
