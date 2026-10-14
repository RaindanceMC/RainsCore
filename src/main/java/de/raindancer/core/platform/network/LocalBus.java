package de.raindancer.core.platform.network;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A bus inside one JVM: what a server on its own uses (there is nobody to tell), and what tests use
 * to stand several servers side by side over one {@link Hub}. Delivers on its own thread, in order,
 * and never to the publisher — exactly as {@link PostgresBus} does, so a store cannot tell them apart.
 */
public final class LocalBus implements NetworkBus {

    private final Hub hub;
    private final String server;
    private final Map<String, List<Consumer<NetworkEvent>>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService dispatch;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private LocalBus(Hub hub, String server) {
        this.hub = hub;
        this.server = server;
        this.dispatch = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rains-localbus-" + server);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** A server with no network: publishing tells nobody. */
    public static LocalBus alone(String server) {
        return new Hub().bus(server);
    }

    @Override
    public String server() {
        return server;
    }

    @Override
    public boolean publish(String topic, String key, String payload) {
        if (topic == null || topic.isBlank() || !open.get()) {
            return false;
        }
        NetworkEvent event = new NetworkEvent(hub.ids.incrementAndGet(), System.currentTimeMillis(), server,
                topic, key, payload);
        for (LocalBus other : hub.buses) {
            if (other != this) {
                other.deliver(event);
            }
        }
        return true;
    }

    @Override
    public Subscription subscribe(String topic, Consumer<NetworkEvent> handler) {
        List<Consumer<NetworkEvent>> forTopic = handlers.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>());
        forTopic.add(handler);
        return () -> forTopic.remove(handler);
    }

    private void deliver(NetworkEvent event) {
        List<Consumer<NetworkEvent>> forTopic = handlers.get(event.topic());
        if (forTopic == null || forTopic.isEmpty() || !open.get()) {
            return;
        }
        dispatch.execute(() -> forTopic.forEach(handler -> {
            try {
                handler.accept(event);
            } catch (RuntimeException ignored) {
                // As on the real bus: one broken handler does not stop the others.
            }
        }));
    }

    /** Waits until everything handed to this bus so far has been delivered. For tests. */
    public void drain() {
        try {
            dispatch.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (Exception interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            hub.buses.remove(this);
            dispatch.shutdown();
        }
    }

    /** Servers that hear each other. */
    public static final class Hub {
        private final List<LocalBus> buses = new CopyOnWriteArrayList<>();
        private final AtomicLong ids = new AtomicLong();

        public LocalBus bus(String server) {
            LocalBus bus = new LocalBus(this, server);
            buses.add(bus);
            return bus;
        }

        /** Waits until every bus on the hub has delivered what it was handed. */
        public void drain() {
            buses.forEach(LocalBus::drain);
        }
    }
}
