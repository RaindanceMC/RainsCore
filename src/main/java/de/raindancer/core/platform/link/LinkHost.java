package de.raindancer.core.platform.link;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The listening end of a {@link ServerLink}.
 *
 * <p>Two endpoints: {@code POST /send}, where the guest delivers messages and gets answers to its questions in the
 * response, and {@code GET /poll}, where it collects what is waiting for it. A poll waits up to the poll time for
 * something to arrive, so a message from here is delivered within a moment, not at the next interval.
 *
 * <p>A message stays in the outbox until the guest's next poll acknowledges it, so one lost response costs a repeat,
 * not the message. Each start has its own instance id: a guest that polls a restarted host is told so and starts its
 * count again, rather than acknowledging messages it has never seen.
 */
public final class LinkHost extends AbstractLink {

    /** More than this waiting for a guest that never comes, and the oldest go — a link is not a mail queue. */
    private static final int MOST_WAITING = 10_000;

    /** No real message comes near this; anything larger is refused before it is read into memory. */
    public static final int MOST_BODY_BYTES = 1 << 20;

    /** Threads serving requests, and how many of them a held-open poll may take — the rest stay free to send. */
    private static final int THREADS = 12;
    private static final int MOST_POLLS = 4;
    private static final int QUEUED = 64;

    /** Nonces accepted inside the signature window; past this many the window is simply full and refuses. */
    private static final int MOST_NONCES = 200_000;

    private final HttpServer server;
    private final ExecutorService threads;
    private final String secret;
    private final Duration pollWait;
    private final String instance = UUID.randomUUID().toString();
    private final Deque<LinkMessage> outbox = new ConcurrentLinkedDeque<>();
    private final Object arrived = new Object();
    private final java.util.concurrent.Semaphore polls = new java.util.concurrent.Semaphore(MOST_POLLS);
    private final Map<String, Long> nonces = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long lastPoll;
    private volatile boolean closed;

    private LinkHost(HttpServer server, ExecutorService threads, String secret, Duration pollWait) {
        this.server = server;
        this.threads = threads;
        this.secret = secret;
        this.pollWait = pollWait;
    }

    /**
     * @param address  where to listen — a private address; this is not for the open internet
     * @param port     0 for any free port, see {@link #port()}
     * @param pollWait how long a poll is held open waiting for something to send
     */
    public static LinkHost listen(String address, int port, String secret, Duration pollWait) throws IOException {
        LinkAuth.requireStrong(secret);
        // The JDK server's own limits on a slow or idle client; only set when the owner has not set them.
        System.setProperty("sun.net.httpserver.maxReqTime",
                System.getProperty("sun.net.httpserver.maxReqTime", "15"));
        System.setProperty("sun.net.httpserver.maxReqHeaders",
                System.getProperty("sun.net.httpserver.maxReqHeaders", "64"));
        HttpServer server = HttpServer.create(new InetSocketAddress(address, port), 0);
        // A bounded queue: past it, a request is turned away rather than kept in memory until there is a thread.
        ExecutorService threads = new java.util.concurrent.ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(QUEUED), task -> {
                    Thread thread = new Thread(task, "link-host");
                    thread.setDaemon(true);
                    return thread;
                }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        LinkHost host = new LinkHost(server, threads, secret, pollWait);
        server.createContext("/send", host::onSend);
        server.createContext("/poll", host::onPoll);
        server.setExecutor(threads);
        server.start();
        return host;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void send(String topic, Map<String, String> data) {
        queue(new LinkMessage(nextId(), topic, data, 0, false));
    }

    @Override
    public CompletableFuture<Map<String, String>> request(String topic, Map<String, String> data, Duration timeout) {
        long id = nextId();
        CompletableFuture<Map<String, String>> answer = expect(id, timeout);
        queue(new LinkMessage(id, topic, data, 0, true));
        return answer;
    }

    private void queue(LinkMessage message) {
        if (closed) {
            return;
        }
        outbox.add(message);
        while (outbox.size() > MOST_WAITING) {
            LinkMessage dropped = outbox.pollFirst();
            if (dropped != null && dropped.ask()) {
                fail(dropped.id(), new IllegalStateException("nobody collected the request"));
            }
        }
        synchronized (arrived) {
            arrived.notifyAll();
        }
    }

    @Override
    public boolean isConnected() {
        return !closed && System.currentTimeMillis() - lastPoll < pollWait.toMillis() * 2 + 2000;
    }

    private void onSend(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = authorised(exchange, "POST");
            if (body == null) {
                return;
            }
            List<LinkMessage> messages;
            try {
                messages = LinkCodec.decode(body);
            } catch (IllegalArgumentException malformed) {
                respond(exchange, 400, "[]");
                return;
            }
            List<LinkMessage> answers = new ArrayList<>();
            for (LinkMessage message : messages) {
                LinkMessage answer = receive(message);
                if (answer != null) {
                    answers.add(answer);
                }
            }
            answer(exchange, LinkCodec.encode(answers));
        }
    }

    private void onPoll(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (authorised(exchange, "GET") == null) {
                return;
            }
            if (!polls.tryAcquire()) {
                respond(exchange, 429, "[]");
                return;
            }
            try {
                hold(exchange);
            } finally {
                polls.release();
            }
        }
    }

    private void hold(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange.getRequestURI());
        lastPoll = System.currentTimeMillis();
        if (instance.equals(query.get("instance"))) {
            long seen = parse(query.get("after"));
            outbox.removeIf(message -> message.id() <= seen);
        }
        long until = System.currentTimeMillis() + pollWait.toMillis();
        synchronized (arrived) {
            while (outbox.isEmpty() && !closed) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    arrived.wait(left);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        lastPoll = System.currentTimeMillis();
        exchange.getResponseHeaders().set("X-Link-Instance", instance);
        answer(exchange, LinkCodec.encode(List.copyOf(outbox)));
    }

    /** A 200 with the body signed over the nonce of the request it answers. */
    private void answer(HttpExchange exchange, String body) throws IOException {
        long time = System.currentTimeMillis();
        String requestNonce = exchange.getRequestHeaders().getFirst(LinkAuth.NONCE);
        exchange.getResponseHeaders().set(LinkAuth.TIME, String.valueOf(time));
        // The instance id travels in the signed body's company: signing it too stops a forged restart.
        String instanceHeader = exchange.getResponseHeaders().getFirst("X-Link-Instance");
        String signed = (instanceHeader == null ? "" : instanceHeader) + "\n" + body;
        exchange.getResponseHeaders().set(LinkAuth.SIGNATURE, LinkAuth.signAnswer(secret, time, requestNonce, signed));
        respond(exchange, 200, body);
    }

    /** @return the body when the request is signed right, or null after answering 401/405 */
    private String authorised(HttpExchange exchange, String method) throws IOException {
        if (!method.equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "[]");
            return null;
        }
        long now = System.currentTimeMillis();
        String time = exchange.getRequestHeaders().getFirst(LinkAuth.TIME);
        // The cheap refusal first: without a fresh time nothing of the body is worth reading.
        if (!LinkAuth.isFresh(time, now)) {
            respond(exchange, 401, "[]");
            return null;
        }
        byte[] read = exchange.getRequestBody().readNBytes(MOST_BODY_BYTES + 1);
        if (read.length > MOST_BODY_BYTES) {
            respond(exchange, 413, "[]");
            return null;
        }
        String body = new String(read, StandardCharsets.UTF_8);
        String signed = method.equals("GET") ? exchange.getRequestURI().getRawQuery() : body;
        String nonce = exchange.getRequestHeaders().getFirst(LinkAuth.NONCE);
        if (!LinkAuth.verify(secret, time, nonce, exchange.getRequestHeaders().getFirst(LinkAuth.SIGNATURE),
                signed == null ? "" : signed, now) || !firstUse(nonce, now)) {
            respond(exchange, 401, "[]");
            return null;
        }
        return body;
    }

    /** Whether this nonce is new inside the window — the half of replay protection the time alone cannot give. */
    private boolean firstUse(String nonce, long now) {
        if (nonces.size() > MOST_NONCES / 2) {
            nonces.values().removeIf(seen -> now - seen > 2 * LinkAuth.MOST_SKEW_MILLIS);
        }
        if (nonces.size() >= MOST_NONCES) {
            return false;
        }
        return nonces.putIfAbsent(nonce, now) == null;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new java.util.HashMap<>();
        String raw = uri.getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    values.put(pair.substring(0, equals), pair.substring(equals + 1));
                }
            }
        }
        return values;
    }

    private static long parse(String number) {
        try {
            return number == null ? 0 : Long.parseLong(number);
        } catch (NumberFormatException garbage) {
            return 0;
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (arrived) {
            arrived.notifyAll();
        }
        server.stop(0);
        threads.shutdownNow();
        try {
            threads.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        failAll(new IllegalStateException("the link was closed"));
    }
}
