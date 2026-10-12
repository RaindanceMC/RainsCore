package de.raindancer.core.platform.link;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The calling end of a {@link ServerLink}: sends by posting to the host, receives by polling it on a thread of its own.
 * A host that is down is retried every {@code retry} until it is back — neither side has to start first.
 */
public final class LinkGuest extends AbstractLink {

    /** Reads an answer up to the size the host would ever send, and fails past it instead of filling memory. */
    private static final HttpResponse.BodyHandler<String> LIMITED = info ->
            HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofInputStream(), in -> {
                try (in) {
                    byte[] bytes = in.readNBytes(LinkHost.MOST_BODY_BYTES * 4 + 1);
                    if (bytes.length > LinkHost.MOST_BODY_BYTES * 4) {
                        throw new java.io.UncheckedIOException(new IOException("the answer is too large"));
                    }
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });

    private final HttpClient client;
    private final URI base;
    private final String secret;
    private final Duration pollWait;
    private final Duration retry;
    private final Thread poller;
    private volatile String instance = "";
    private volatile long seen;
    private volatile long lastHeard;
    private volatile boolean closed;

    private LinkGuest(URI base, String secret, Duration pollWait, Duration retry) {
        this.base = base;
        this.secret = secret;
        this.pollWait = pollWait;
        this.retry = retry;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.poller = new Thread(this::pollForever, "link-guest");
        this.poller.setDaemon(true);
    }

    /**
     * @param base     the host, e.g. {@code http://172.18.0.1:25594}
     * @param pollWait how long the host holds a poll open — the same value the host was given
     * @param retry    the pause after a failed poll
     */
    public static LinkGuest connect(URI base, String secret, Duration pollWait, Duration retry) {
        LinkAuth.requireStrong(secret);
        LinkGuest guest = new LinkGuest(base, secret, pollWait, retry);
        guest.poller.start();
        return guest;
    }

    @Override
    public void send(String topic, Map<String, String> data) {
        post(List.of(new LinkMessage(nextId(), topic, data, 0, false))).exceptionally(failure -> {
            log.warn("A '{}' message could not be delivered: {}", topic, failure.getMessage());
            return null;
        });
    }

    @Override
    public CompletableFuture<Map<String, String>> request(String topic, Map<String, String> data, Duration timeout) {
        long id = nextId();
        CompletableFuture<Map<String, String>> answer = expect(id, timeout);
        post(List.of(new LinkMessage(id, topic, data, 0, true))).whenComplete((sent, failure) -> {
            if (failure != null) {
                fail(id, failure);
            }
        });
        return answer;
    }

    @Override
    public boolean isConnected() {
        return !closed && System.currentTimeMillis() - lastHeard < pollWait.toMillis() * 2 + 2000;
    }

    /** Posts messages; answers that come back in the response are handed to whoever asked. */
    private CompletableFuture<Void> post(List<LinkMessage> messages) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("the link was closed"));
        }
        String body = LinkCodec.encode(messages);
        long time = System.currentTimeMillis();
        String nonce = LinkAuth.nonce();
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/send"))
                .timeout(Duration.ofSeconds(10))
                .header(LinkAuth.TIME, String.valueOf(time))
                .header(LinkAuth.NONCE, nonce)
                .header(LinkAuth.SIGNATURE, LinkAuth.sign(secret, time, nonce, body))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.sendAsync(request, LIMITED).thenAccept(response -> {
            if (response.statusCode() != 200) {
                throw new IllegalStateException("the host answered " + response.statusCode());
            }
            if (!genuine(response, nonce)) {
                throw new IllegalStateException("the answer was not signed by the host");
            }
            lastHeard = System.currentTimeMillis();
            for (LinkMessage answer : LinkCodec.decode(response.body())) {
                receive(answer);
            }
        });
    }

    private void pollForever() {
        while (!closed) {
            try {
                pollOnce();
            } catch (IOException | RuntimeException failure) {
                pause();
            } catch (InterruptedException interrupted) {
                return;
            }
        }
    }

    private void pollOnce() throws IOException, InterruptedException {
        String query = "instance=" + instance + "&after=" + seen;
        long time = System.currentTimeMillis();
        String nonce = LinkAuth.nonce();
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/poll?" + query))
                .timeout(pollWait.plusSeconds(5))
                .header(LinkAuth.TIME, String.valueOf(time))
                .header(LinkAuth.NONCE, nonce)
                .header(LinkAuth.SIGNATURE, LinkAuth.sign(secret, time, nonce, query))
                .GET().build();
        HttpResponse<String> response = client.send(request, LIMITED);
        if (response.statusCode() != 200 || !genuine(response, nonce)) {
            pause();
            return;
        }
        lastHeard = System.currentTimeMillis();
        String from = response.headers().firstValue("X-Link-Instance").orElse("");
        if (!from.equals(instance)) {
            // A host that restarted numbers from one again; what was acknowledged before means nothing to it.
            instance = from;
            seen = 0;
        }
        List<LinkMessage> answers = new ArrayList<>();
        for (LinkMessage message : LinkCodec.decode(response.body())) {
            if (message.id() <= seen) {
                continue;
            }
            seen = message.id();
            LinkMessage answer = receive(message);
            if (answer != null) {
                answers.add(answer);
            }
        }
        if (!answers.isEmpty()) {
            post(answers).exceptionally(failure -> {
                log.warn("Answers to the host could not be delivered: {}", failure.getMessage());
                return null;
            });
        }
    }

    /** Whether the host really said this, to the request with this nonce — instance id and body both. */
    private boolean genuine(HttpResponse<String> response, String nonce) {
        String instanceHeader = response.headers().firstValue("X-Link-Instance").orElse("");
        return LinkAuth.verifyAnswer(secret, response.headers().firstValue(LinkAuth.TIME).orElse(null), nonce,
                response.headers().firstValue(LinkAuth.SIGNATURE).orElse(null),
                instanceHeader + "\n" + response.body(), System.currentTimeMillis());
    }

    private void pause() {
        try {
            Thread.sleep(retry.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            closed = true;
        }
    }

    @Override
    public void close() {
        closed = true;
        poller.interrupt();
        failAll(new IllegalStateException("the link was closed"));
    }
}
