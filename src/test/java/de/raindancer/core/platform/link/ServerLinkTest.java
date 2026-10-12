package de.raindancer.core.platform.link;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two servers talking over a real socket on this machine: one listens, the other calls in and polls for what is
 * waiting for it. Every test here opens real ports, because what can go wrong is the network and the order of things.
 */
class ServerLinkTest {

    private static final String SECRET = "a-shared-secret-of-reasonable-length";

    private final List<ServerLink> open = new ArrayList<>();

    @AfterEach
    void closeAll() {
        open.forEach(ServerLink::close);
    }

    private LinkHost host(String secret) throws IOException {
        LinkHost host = LinkHost.listen("127.0.0.1", 0, secret, Duration.ofSeconds(2));
        open.add(host);
        return host;
    }

    private LinkGuest guest(LinkHost host, String secret) {
        LinkGuest guest = LinkGuest.connect(URI.create("http://127.0.0.1:" + host.port()), secret,
                Duration.ofSeconds(2), Duration.ofMillis(50));
        open.add(guest);
        return guest;
    }

    private static void eventually(Runnable check) throws InterruptedException {
        AssertionError last = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                check.run();
                return;
            } catch (AssertionError notYet) {
                last = notYet;
                Thread.sleep(50);
            }
        }
        throw last;
    }

    @Test
    @DisplayName("what the guest sends arrives at the host, under its topic")
    void guestToHost() throws Exception {
        LinkHost host = host(SECRET);
        List<Map<String, String>> got = new CopyOnWriteArrayList<>();
        host.on("chat", data -> {
            got.add(data);
            return null;
        });
        LinkGuest guest = guest(host, SECRET);

        guest.send("chat", Map.of("from", "Lilly", "text", "hello arena"));

        eventually(() -> assertThat(got).containsExactly(Map.of("from", "Lilly", "text", "hello arena")));
    }

    @Test
    @DisplayName("what the host sends waits for the guest's next poll and arrives once, in order")
    void hostToGuest() throws Exception {
        LinkHost host = host(SECRET);
        LinkGuest guest = guest(host, SECRET);
        List<String> got = new CopyOnWriteArrayList<>();
        guest.on("result", data -> {
            got.add(data.get("n"));
            return null;
        });

        for (int n = 1; n <= 5; n++) {
            host.send("result", Map.of("n", String.valueOf(n)));
        }

        eventually(() -> assertThat(got).containsExactly("1", "2", "3", "4", "5"));
        Thread.sleep(300);
        assertThat(got).as("a message the guest has acknowledged is not handed out again").hasSize(5);
    }

    @Test
    @DisplayName("a request from the guest gets the host's answer back")
    void guestRequest() throws Exception {
        LinkHost host = host(SECRET);
        host.on("balance", data -> Map.of("player", data.get("player"), "balance", "120"));
        LinkGuest guest = guest(host, SECRET);

        Map<String, String> answer = guest.request("balance", Map.of("player", "Lilly"), Duration.ofSeconds(2))
                .get(3, TimeUnit.SECONDS);

        assertThat(answer).containsEntry("balance", "120").containsEntry("player", "Lilly");
    }

    @Test
    @DisplayName("a request from the host gets the guest's answer back, through the poll")
    void hostRequest() throws Exception {
        LinkHost host = host(SECRET);
        LinkGuest guest = guest(host, SECRET);
        guest.on("escrow", data -> Map.of("ok", "true", "amount", data.get("amount")));
        eventually(() -> assertThat(host.isConnected()).isTrue());

        Map<String, String> answer = host.request("escrow", Map.of("amount", "50"), Duration.ofSeconds(3))
                .get(4, TimeUnit.SECONDS);

        assertThat(answer).containsEntry("ok", "true").containsEntry("amount", "50");
    }

    @Test
    @DisplayName("a request nobody answers in time fails rather than waiting for ever")
    void requestTimesOut() throws Exception {
        LinkHost host = host(SECRET);
        LinkGuest guest = guest(host, SECRET);
        guest.on("slow", data -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return Map.of();
        });
        eventually(() -> assertThat(host.isConnected()).isTrue());

        assertThatThrownBy(() -> host.request("slow", Map.of(), Duration.ofMillis(300)).get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("a topic nobody handles answers with nothing, and nothing breaks")
    void unknownTopic() throws Exception {
        LinkHost host = host(SECRET);
        LinkGuest guest = guest(host, SECRET);

        Map<String, String> answer = guest.request("nobody-listens", Map.of(), Duration.ofSeconds(2))
                .get(3, TimeUnit.SECONDS);

        assertThat(answer).isEmpty();
    }

    @Test
    @DisplayName("somebody with the wrong secret is refused, both sending and polling")
    void wrongSecretRefused() throws Exception {
        LinkHost host = host(SECRET);
        List<Map<String, String>> got = new CopyOnWriteArrayList<>();
        host.on("chat", data -> {
            got.add(data);
            return null;
        });
        LinkGuest impostor = guest(host, "not-the-secret-at-all-not-even-close");

        assertThatThrownBy(() -> impostor.request("chat", Map.of("text", "hi"), Duration.ofSeconds(1))
                .get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        Thread.sleep(200);
        assertThat(got).isEmpty();
        assertThat(host.isConnected()).as("a refused poll is not a connection").isFalse();
    }

    private static HttpResponse<String> post(LinkHost host, String body, long time, String nonce, String signature)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port() + "/send"))
                .header(LinkAuth.TIME, String.valueOf(time))
                .header(LinkAuth.NONCE, nonce)
                .header(LinkAuth.SIGNATURE, signature)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a signed request replayed later is refused — an old message cannot be sent again")
    void staleRefused() throws Exception {
        LinkHost host = host(SECRET);
        List<Map<String, String>> got = new CopyOnWriteArrayList<>();
        host.on("pay", data -> {
            got.add(data);
            return null;
        });
        String body = LinkCodec.encode(List.of(new LinkMessage(1, "pay", Map.of("amount", "100"), 0, false)));
        long stale = System.currentTimeMillis() - 120_000;

        HttpResponse<String> response = post(host, body, stale, "0123456789abcdef01", LinkAuth.sign(SECRET, stale, "0123456789abcdef01", body));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("the same signed request sent twice inside the time window counts once")
    void duplicateNonceRefused() throws Exception {
        LinkHost host = host(SECRET);
        List<Map<String, String>> got = new CopyOnWriteArrayList<>();
        host.on("pay", data -> {
            got.add(data);
            return null;
        });
        String body = LinkCodec.encode(List.of(new LinkMessage(1, "pay", Map.of("amount", "100"), 0, false)));
        long now = System.currentTimeMillis();
        String signature = LinkAuth.sign(SECRET, now, "00112233445566778899aabbccddeeff", body);

        assertThat(post(host, body, now, "00112233445566778899aabbccddeeff", signature).statusCode()).isEqualTo(200);
        assertThat(post(host, body, now, "00112233445566778899aabbccddeeff", signature).statusCode()).isEqualTo(401);
        assertThat(got).hasSize(1);
    }

    @Test
    @DisplayName("a body larger than any real message is refused without being read into memory")
    void oversizedRefused() throws Exception {
        LinkHost host = host(SECRET);
        String body = "x".repeat(LinkHost.MOST_BODY_BYTES + 10);
        long now = System.currentTimeMillis();

        HttpResponse<String> response = post(host, body, now, "b1b1b1b1b1b1b1b1b1", LinkAuth.sign(SECRET, now, "b1b1b1b1b1b1b1b1b1", body));

        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    @DisplayName("a guest believes nothing that is not signed by the host — not a poll, not an answer")
    void unsignedAnswersIgnored() throws Exception {
        com.sun.net.httpserver.HttpServer fake = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String forged = LinkCodec.encode(List.of(new LinkMessage(1, "payout", Map.of("to", "mallory"), 0, false)));
        fake.createContext("/", exchange -> {
            byte[] bytes = forged.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("X-Link-Instance", "fake");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fake.start();
        try {
            LinkGuest guest = LinkGuest.connect(URI.create("http://127.0.0.1:" + fake.getAddress().getPort()),
                    SECRET, Duration.ofMillis(200), Duration.ofMillis(50));
            open.add(guest);
            List<Map<String, String>> got = new CopyOnWriteArrayList<>();
            guest.on("payout", data -> {
                got.add(data);
                return null;
            });

            Thread.sleep(600);

            assertThat(got).isEmpty();
            assertThat(guest.isConnected()).isFalse();
            assertThatThrownBy(() -> guest.request("x", Map.of(), Duration.ofMillis(500)).get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
        } finally {
            fake.stop(0);
        }
    }

    @Test
    @DisplayName("a guest that loses the host keeps trying, and is connected again once the host is back")
    void guestReconnects() throws Exception {
        LinkHost first = host(SECRET);
        int port = first.port();
        LinkGuest guest = guest(first, SECRET);
        List<String> got = new CopyOnWriteArrayList<>();
        guest.on("hello", data -> {
            got.add(data.get("from"));
            return null;
        });
        eventually(() -> assertThat(first.isConnected()).isTrue());

        first.close();
        LinkHost second = LinkHost.listen("127.0.0.1", port, SECRET, Duration.ofSeconds(2));
        open.add(second);
        second.send("hello", Map.of("from", "second"));

        eventually(() -> assertThat(got).containsExactly("second"));
    }

    @Test
    @DisplayName("a short secret is refused up front — a guessable secret is no lock at all")
    void shortSecretRefused() {
        assertThatThrownBy(() -> LinkHost.listen("127.0.0.1", 0, "short", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("messages survive the trip with any text in them")
    void codecRoundTrip() {
        List<LinkMessage> messages = List.of(
                new LinkMessage(7, "chat", Map.of("text", "äöü \"quoted\" \n new line <red>tags</red> ✓"), 0, true),
                new LinkMessage(8, "reply", Map.of(), 7, false));

        assertThat(LinkCodec.decode(LinkCodec.encode(messages))).isEqualTo(messages);
    }

    @Test
    @DisplayName("a request's signature and an answer's are never interchangeable, whatever the nonce says")
    void signaturesAreSeparated() {
        long time = 1_000L;
        String asAnswer = LinkAuth.signAnswer(SECRET, time, "abc", "body");

        assertThat(LinkAuth.sign(SECRET, time, "answer:abc", "body")).isNotEqualTo(asAnswer);
        assertThat(LinkAuth.verify(SECRET, String.valueOf(System.currentTimeMillis()), "answer:abc", asAnswer, "body",
                System.currentTimeMillis())).as("a nonce is plain hex, nothing else").isFalse();
        assertThat(LinkAuth.sign(SECRET, time, "ab", "c\nbody")).as("where one field ends is part of what is signed")
                .isNotEqualTo(LinkAuth.sign(SECRET, time, "ab\nc", "body"));
    }

    @Test
    @DisplayName("a guest does not read an answer larger than any real one into memory")
    void oversizedAnswerRefused() throws Exception {
        com.sun.net.httpserver.HttpServer fake = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                byte[] chunk = new byte[64 * 1024];
                for (int written = 0; written < LinkHost.MOST_BODY_BYTES * 3; written += chunk.length) {
                    out.write(chunk);
                }
            } catch (IOException closedByTheGuest) {
                // The guest hanging up partway is exactly what is being tested.
            }
        });
        fake.start();
        try {
            LinkGuest guest = LinkGuest.connect(URI.create("http://127.0.0.1:" + fake.getAddress().getPort()),
                    SECRET, Duration.ofMillis(200), Duration.ofMillis(50));
            open.add(guest);

            assertThatThrownBy(() -> guest.request("x", Map.of(), Duration.ofSeconds(2)).get(3, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
        } finally {
            fake.stop(0);
        }
    }

    /** A host that answers every poll at once, signed properly, with whatever body the test gives it. */
    private static com.sun.net.httpserver.HttpServer signingFake(java.util.function.Supplier<String> body,
                                                                 List<String> queries) throws IOException {
        com.sun.net.httpserver.HttpServer fake = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/poll", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            String text = body.get();
            long time = System.currentTimeMillis();
            String nonce = exchange.getRequestHeaders().getFirst(LinkAuth.NONCE);
            exchange.getResponseHeaders().set("X-Link-Instance", "fake-instance");
            exchange.getResponseHeaders().set(LinkAuth.TIME, String.valueOf(time));
            exchange.getResponseHeaders().set(LinkAuth.SIGNATURE,
                    LinkAuth.signAnswer(SECRET, time, nonce, "fake-instance\n" + text));
            byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fake.start();
        return fake;
    }

    @Test
    @DisplayName("one message that cannot be read is skipped; the others arrive and the poll moves past all of them")
    void poisonMessageDoesNotStall() throws Exception {
        List<String> queries = new CopyOnWriteArrayList<>();
        String body = "[{\"id\":1,\"topic\":\"t\",\"data\":{\"n\":\"one\"}},"
                + "{\"id\":2,\"topic\":\"t\",\"data\":\"not an object\"},"
                + "{\"id\":3,\"topic\":\"t\",\"data\":{\"n\":\"three\"}}]";
        com.sun.net.httpserver.HttpServer fake = signingFake(() -> body, queries);
        try {
            LinkGuest guest = LinkGuest.connect(URI.create("http://127.0.0.1:" + fake.getAddress().getPort()),
                    SECRET, Duration.ofMillis(200), Duration.ofMillis(50));
            open.add(guest);
            List<String> got = new CopyOnWriteArrayList<>();
            guest.on("t", data -> {
                got.add(data.get("n"));
                return null;
            });

            eventually(() -> assertThat(queries).anyMatch(query -> query.endsWith("after=3")));
            assertThat(got).startsWith("one", "three");
        } finally {
            fake.stop(0);
        }
    }

    @Test
    @DisplayName("a host that answers every poll at once does not drive the guest into a busy loop")
    void noBusyLoop() throws Exception {
        List<String> queries = new CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer fake = signingFake(() -> "[]", queries);
        try {
            LinkGuest guest = LinkGuest.connect(URI.create("http://127.0.0.1:" + fake.getAddress().getPort()),
                    SECRET, Duration.ofSeconds(20), Duration.ofMillis(50));
            open.add(guest);

            Thread.sleep(1000);

            assertThat(queries.size()).as("polls in one second").isLessThanOrEqualTo(12);
        } finally {
            fake.stop(0);
        }
    }
}
