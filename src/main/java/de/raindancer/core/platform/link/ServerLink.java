package de.raindancer.core.platform.link;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Two servers of one network talking to each other directly — what a proxy's plugin messages cannot do, because those
 * ride on a player's connection and stop the moment nobody is online on one side. A payout due after the last fighter
 * has left still has to arrive.
 *
 * <p>One side listens ({@link LinkHost}), the other calls in and polls ({@link LinkGuest}) — so only one of them needs a
 * port the other can reach. Either side can send, ask and answer. Messages are signed with a shared secret
 * ({@link LinkAuth}); meant for servers on one machine or one private network, not for the open internet.
 *
 * <p>Handlers run on the link's own threads and must hop to the server's scheduler before touching the world.
 */
public interface ServerLink extends AutoCloseable {

    /** Delivered when the other side is reachable; dropped with a log line when it is not. */
    void send(String topic, Map<String, String> data);

    /**
     * Asks the other side and waits for the answer — the handler's return value, empty when it had none. Fails with a
     * {@link java.util.concurrent.TimeoutException} when no answer comes in time.
     */
    CompletableFuture<Map<String, String>> request(String topic, Map<String, String> data, Duration timeout);

    /**
     * What to do with messages of a topic. The return value answers a request; null is "no answer", which a request
     * receives as an empty map. One handler per topic; a second replaces the first.
     */
    void on(String topic, Function<Map<String, String>, Map<String, String>> handler);

    /** Whether the other side has been heard from recently. */
    boolean isConnected();

    @Override
    void close();
}
