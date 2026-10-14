package de.raindancer.core.platform.network;

import de.raindancer.core.data.sql.PostgresPool;
import de.raindancer.core.testkit.TestPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class PostgresBusTest {

    private final List<AutoCloseable> closing = new ArrayList<>();

    /** A bus on a channel of its own, so tests running together never hear each other. */
    private PostgresBus bus(String server, String channel) {
        PostgresPool pool = PostgresPool.open(TestPostgres.require().withPoolSize(3));
        PostgresBus bus = PostgresBus.start(pool, server, channel, Duration.ofMillis(300));
        closing.add(bus);
        closing.add(pool);
        return bus;
    }

    @AfterEach
    void closeAll() throws Exception {
        for (int at = closing.size() - 1; at >= 0; at--) {
            closing.get(at).close();
        }
    }

    private static String channel() {
        return TestPostgres.freshNamespace("bus");
    }

    @Test
    @DisplayName("what one server publishes, every other server hears — and the publisher does not")
    void deliveredToOthers() {
        String channel = channel();
        PostgresBus smp = bus("smp", channel);
        PostgresBus arena = bus("arena", channel);
        PostgresBus hub = bus("hub", channel);
        List<NetworkEvent> atSmp = new CopyOnWriteArrayList<>();
        List<NetworkEvent> atArena = new CopyOnWriteArrayList<>();
        List<NetworkEvent> atHub = new CopyOnWriteArrayList<>();
        smp.subscribe("punishments", atSmp::add);
        arena.subscribe("punishments", atArena::add);
        hub.subscribe("punishments", atHub::add);

        smp.publish("punishments", "c0ffee00-0000-0000-0000-000000000001", "ban");

        await().atMost(5, TimeUnit.SECONDS).until(() -> atArena.size() == 1 && atHub.size() == 1);
        NetworkEvent heard = atArena.getFirst();
        assertThat(heard.topic()).isEqualTo("punishments");
        assertThat(heard.key()).isEqualTo("c0ffee00-0000-0000-0000-000000000001");
        assertThat(heard.payload()).isEqualTo("ban");
        assertThat(heard.server()).isEqualTo("smp");
        assertThat(atSmp).as("the publisher already knows what it changed").isEmpty();
    }

    @Test
    @DisplayName("fast: a notify wakes the listener, it does not wait for the next poll")
    void fast() {
        String channel = channel();
        PostgresBus smp = bus("smp", channel);
        PostgresBus arena = bus("arena", channel);
        List<Long> heardAfter = new CopyOnWriteArrayList<>();
        long[] sent = new long[1];
        arena.subscribe("chat", event -> heardAfter.add(System.nanoTime() - sent[0]));
        sent[0] = System.nanoTime();
        smp.publish("chat", "", "hello");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !heardAfter.isEmpty());
        assertThat(TimeUnit.NANOSECONDS.toMillis(heardAfter.getFirst())).isLessThan(250);
    }

    @Test
    @DisplayName("only the topics subscribed to are handed over, in the order they were published")
    void topicsAndOrder() {
        String channel = channel();
        PostgresBus smp = bus("smp", channel);
        PostgresBus arena = bus("arena", channel);
        List<String> heard = new CopyOnWriteArrayList<>();
        arena.subscribe("identity", event -> heard.add(event.key()));
        for (int at = 0; at < 50; at++) {
            smp.publish(at % 2 == 0 ? "identity" : "other", "k" + at, "");
        }
        await().atMost(5, TimeUnit.SECONDS).until(() -> heard.size() == 25);
        List<String> expected = new ArrayList<>();
        for (int at = 0; at < 50; at += 2) {
            expected.add("k" + at);
        }
        assertThat(heard).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("a handler that throws does not stop the others or the bus")
    void handlerFailureIsContained() {
        String channel = channel();
        PostgresBus smp = bus("smp", channel);
        PostgresBus arena = bus("arena", channel);
        List<String> heard = new CopyOnWriteArrayList<>();
        arena.subscribe("x", event -> {
            throw new IllegalStateException("broken handler");
        });
        arena.subscribe("x", event -> heard.add(event.payload()));
        smp.publish("x", "", "one");
        smp.publish("x", "", "two");
        await().atMost(5, TimeUnit.SECONDS).until(() -> heard.size() == 2);
    }

    @Test
    @DisplayName("a listener whose connection was killed reconnects and catches up on what it missed")
    void catchesUpAfterReconnect() throws Exception {
        String channel = channel();
        PostgresBus smp = bus("smp", channel);
        PostgresBus arena = bus("arena", channel);
        List<String> heard = new CopyOnWriteArrayList<>();
        arena.subscribe("t", event -> heard.add(event.payload()));
        smp.publish("t", "", "before");
        await().atMost(5, TimeUnit.SECONDS).until(() -> heard.size() == 1);

        try (Connection admin = TestPostgres.connect(); Statement statement = admin.createStatement()) {
            statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = '"
                    + arena.listenerName() + "'");
        }
        smp.publish("t", "", "while down 1");
        smp.publish("t", "", "while down 2");

        await().atMost(15, TimeUnit.SECONDS).until(() -> heard.size() == 3);
        assertThat(heard).containsExactly("before", "while down 1", "while down 2");
    }

    @Test
    @DisplayName("an event committed after a later one is still delivered — ids are handed out before commit")
    void outOfOrderCommits() throws Exception {
        String channel = channel();
        PostgresBus smp = bus("smp", channel);
        PostgresBus arena = bus("arena", channel);
        List<String> heard = new CopyOnWriteArrayList<>();
        arena.subscribe("t", event -> heard.add(event.payload()));
        smp.publish("t", "", "warm");
        await().atMost(5, TimeUnit.SECONDS).until(() -> heard.size() == 1);

        try (Connection slow = TestPostgres.connect()) {
            slow.setAutoCommit(false);
            try (PreparedStatement insert = slow.prepareStatement(
                    "INSERT INTO public.rains_event (channel, at, server, topic, key, payload) VALUES (?, ?, 'other', 't', '', 'slow')")) {
                insert.setString(1, channel);
                insert.setLong(2, System.currentTimeMillis());
                insert.executeUpdate();
            }
            smp.publish("t", "", "fast");
            await().atMost(5, TimeUnit.SECONDS).until(() -> heard.contains("fast"));
            slow.commit();
        }
        await().atMost(10, TimeUnit.SECONDS).until(() -> heard.contains("slow"));
    }

    @Test
    @DisplayName("a bus started now does not replay what was said before it started")
    void noReplayOfHistory() {
        String channel = channel();
        PostgresBus smp = bus("smp", channel);
        smp.publish("t", "", "old news");
        PostgresBus late = bus("late", channel);
        List<String> heard = new CopyOnWriteArrayList<>();
        late.subscribe("t", event -> heard.add(event.payload()));
        smp.publish("t", "", "fresh");
        await().atMost(5, TimeUnit.SECONDS).until(() -> heard.contains("fresh"));
        assertThat(heard).containsExactly("fresh");
    }

    @Test
    @DisplayName("events older than the keep time are pruned, newer ones kept")
    void pruned() throws Exception {
        String channel = channel();
        PostgresBus smp = bus("smp", channel);
        smp.publish("t", "", "x");
        try (Connection admin = TestPostgres.connect(); Statement statement = admin.createStatement()) {
            statement.execute("UPDATE public.rains_event SET at = 0 WHERE channel = '" + channel + "'");
        }
        smp.publish("t", "", "y");
        assertThat(smp.prune(Duration.ofHours(1))).isEqualTo(1);
        try (Connection admin = TestPostgres.connect(); Statement statement = admin.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM public.rains_event WHERE channel = '" + channel + "'")) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("publishing on a database nobody can reach answers false and does not throw")
    void unreachable() {
        try (PostgresPool nowhere = PostgresPool.open(de.raindancer.core.data.sql.PostgresSettings.of("127.0.0.1", 1, "x", "y", ""));
             PostgresBus bus = PostgresBus.start(nowhere, "smp", channel(), Duration.ofMillis(300))) {
            assertThat(bus.publish("t", "", "lost")).isFalse();
        }
    }
}
