package de.raindancer.core.platform.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkCallsTest {

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final NetworkCalls smp = new NetworkCalls(hub.bus("smp"));
    private final NetworkCalls arena = new NetworkCalls(hub.bus("arena"));
    private final NetworkCalls hubServer = new NetworkCalls(hub.bus("hub"));

    @AfterEach
    void close() {
        smp.close();
        arena.close();
        hubServer.close();
    }

    @Test
    @DisplayName("one server asks another and gets its answer back")
    void askAndAnswer() throws Exception {
        arena.answer("arena.where", question -> Map.of("pit", "pit-" + question.get("player")));
        Optional<Map<String, String>> answer = smp.ask("arena", "arena.where", Map.of("player", "Alex"),
                Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS);
        assertThat(answer).contains(Map.of("pit", "pit-Alex"));
    }

    @Test
    @DisplayName("only the server asked answers")
    void onlyTheOneAsked() throws Exception {
        arena.answer("who", question -> Map.of("me", "arena"));
        hubServer.answer("who", question -> Map.of("me", "hub"));
        assertThat(smp.ask("hub", "who", Map.of(), Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS))
                .contains(Map.of("me", "hub"));
    }

    @Test
    @DisplayName("nobody answering is an empty answer after the timeout, not a hang")
    void timesOut() throws Exception {
        long started = System.nanoTime();
        Optional<Map<String, String>> answer = smp.ask("nowhere", "x", Map.of(), Duration.ofMillis(300))
                .get(10, TimeUnit.SECONDS);
        assertThat(answer).isEmpty();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(250L, 5_000L);
    }

    @Test
    @DisplayName("a handler that throws gives an empty answer, at once")
    void failingHandler() throws Exception {
        arena.answer("boom", question -> {
            throw new IllegalStateException("broken");
        });
        long started = System.nanoTime();
        assertThat(smp.ask("arena", "boom", Map.of(), Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS)).isEmpty();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(4_000L);
    }

    @Test
    @DisplayName("values survive the trip whatever they hold: separators, newlines, unicode, empty")
    void awkwardValues() throws Exception {
        arena.answer("echo", question -> question);
        Map<String, String> awkward = Map.of("a&b=c", "x=y&z", "lines", "one\ntwo", "unicode", "Grüße ✦", "empty", "");
        assertThat(smp.ask("arena", "echo", awkward, Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS)).contains(awkward);
    }

    @Test
    @DisplayName("an answer handler can be taken back")
    void stopAnswering() throws Exception {
        NetworkBus.Subscription answering = arena.answer("x", question -> Map.of("ok", "1"));
        answering.close();
        assertThat(smp.ask("arena", "x", Map.of(), Duration.ofMillis(300)).get(10, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    @DisplayName("an answer given later does not hold up the bus: other questions are answered meanwhile")
    void answerLater() throws Exception {
        java.util.concurrent.CompletableFuture<Map<String, String>> slow = new java.util.concurrent.CompletableFuture<>();
        arena.answerLater("slow", question -> slow);
        arena.answer("quick", question -> Map.of("at", "once"));
        var waiting = smp.ask("arena", "slow", Map.of(), Duration.ofSeconds(5));
        assertThat(smp.ask("arena", "quick", Map.of(), Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS))
                .contains(Map.of("at", "once"));
        assertThat(waiting).isNotDone();
        slow.complete(Map.of("at", "last"));
        assertThat(waiting.get(10, TimeUnit.SECONDS)).contains(Map.of("at", "last"));
    }

    @Test
    @DisplayName("an answer that fails later is an empty answer at once")
    void answerLaterFails() throws Exception {
        arena.answerLater("broken", question -> java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("x")));
        long started = System.nanoTime();
        assertThat(smp.ask("arena", "broken", Map.of(), Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS)).isEmpty();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(4_000L);
    }

    @Test
    @DisplayName("a handler can be told which server asked")
    void answerFrom() throws Exception {
        arena.answerFrom("who-asked", (from, question) -> java.util.concurrent.CompletableFuture.completedFuture(Map.of("you", from)));
        assertThat(hubServer.ask("arena", "who-asked", Map.of(), Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS))
                .contains(Map.of("you", "hub"));
    }
}
