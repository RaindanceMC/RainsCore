package de.raindancer.core.platform.network;

import java.util.function.Consumer;

/**
 * How the servers of a network tell each other that something changed.
 *
 * <p>The database is the truth; the bus only says where to look again. A store writes its change,
 * then publishes {@code (topic, key)}; every other server hears it and reads that key afresh. So an
 * event that arrives twice, or late, costs a read and nothing else — handlers must be idempotent, and
 * a handler should never carry state the database does not also hold.
 *
 * <p>A server does not hear its own events: it already knows what it changed.
 *
 * <p>Handlers run on the bus's own thread, never on a thread running the world. Anything that touches
 * players or blocks hops to the right scheduler first.
 */
public interface NetworkBus extends AutoCloseable {

    /** Nobody to tell and nobody listening: a server on its own, or a fake core in a test. */
    NetworkBus NONE = new NetworkBus() {
        @Override
        public String server() {
            return "";
        }

        @Override
        public boolean publish(String topic, String key, String payload) {
            return true;
        }

        @Override
        public Subscription subscribe(String topic, java.util.function.Consumer<NetworkEvent> handler) {
            return () -> { };
        }

        @Override
        public void close() {
        }
    };

    /** This server's id, as the others see it on its events. */
    String server();

    /**
     * Tells every other server. Called after the change it announces has been committed, never
     * before — a server that hears it too early reads the old value and keeps it.
     *
     * @return whether it was published; false when the network's database cannot be reached
     */
    boolean publish(String topic, String key, String payload);

    default boolean publish(String topic, String key) {
        return publish(topic, key, "");
    }

    /** Hands every event on {@code topic} from another server to {@code handler}, in the order they were published. */
    Subscription subscribe(String topic, Consumer<NetworkEvent> handler);

    @Override
    void close();

    @FunctionalInterface
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
