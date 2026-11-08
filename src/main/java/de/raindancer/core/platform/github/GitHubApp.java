package de.raindancer.core.platform.github;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Signs in to GitHub as a GitHub App: a JWT signed with the app's key, exchanged for an installation token for the
 * repository asked about, kept until shortly before it runs out. Blocking — call it off the main thread.
 */
public final class GitHubApp {

    private static final String API = "https://api.github.com";
    /** A token is given up this long before GitHub would, so a request never leaves with one about to lapse. */
    private static final long MARGIN_MS = 60_000;

    /** How a request reaches GitHub; swapped out in tests. */
    public interface Transport {
        Response send(String method, String url, Map<String, String> headers, String body) throws IOException;
    }

    public record Response(int status, String body) {
    }

    private record Token(String value, long until) {
    }

    private final long appId;
    private final PrivateKey key;
    private final Transport transport;
    private final LongSupplier clock;
    private final Map<String, Long> installations = new ConcurrentHashMap<>();
    private final Map<Long, Token> tokens = new ConcurrentHashMap<>();

    public GitHubApp(long appId, PrivateKey key, Transport transport, LongSupplier clock) {
        this.appId = appId;
        this.key = key;
        this.transport = transport;
        this.clock = clock;
    }

    /** Over the network, with the JDK's own client. */
    public static GitHubApp of(long appId, PrivateKey key) {
        return new GitHubApp(appId, key, http(), System::currentTimeMillis);
    }

    /** One request to the API for {@code repo}, signed in as the app's installation there. */
    public Response call(String repo, String method, String path, String body) throws IOException {
        String token = tokenFor(repo);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        Response answer = transport.send(method, API + path, headers(headers), body);
        if (answer.status() >= 300) {
            throw new IOException("GitHub answered " + answer.status() + ": " + message(answer.body()));
        }
        return answer;
    }

    String jwt() throws IOException {
        long now = clock.getAsLong() / 1000;
        String header = base64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        // Issued a minute back for clocks that run a little behind GitHub's; nine minutes is under its limit of ten.
        String claims = base64("{\"iat\":" + (now - 60) + ",\"exp\":" + (now + 540) + ",\"iss\":\"" + appId + "\"}");
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
            return header + "." + claims + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        } catch (GeneralSecurityException broken) {
            throw new IOException("The app's key could not sign: " + broken.getMessage(), broken);
        }
    }

    private String tokenFor(String repo) throws IOException {
        Long installation = installations.get(repo);
        if (installation == null) {
            Response found = asApp("GET", "/repos/" + repo + "/installation", null);
            installation = JsonParser.parseString(found.body()).getAsJsonObject().get("id").getAsLong();
            installations.put(repo, installation);
        }
        Token token = tokens.get(installation);
        if (token == null || clock.getAsLong() >= token.until()) {
            Response made = asApp("POST", "/app/installations/" + installation + "/access_tokens", "{}");
            JsonObject json = JsonParser.parseString(made.body()).getAsJsonObject();
            long expires = Instant.parse(json.get("expires_at").getAsString()).toEpochMilli();
            token = new Token(json.get("token").getAsString(), expires - MARGIN_MS);
            tokens.put(installation, token);
        }
        return token.value();
    }

    private Response asApp(String method, String path, String body) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + jwt());
        Response answer = transport.send(method, API + path, headers(headers), body);
        if (answer.status() >= 300) {
            throw new IOException("GitHub answered " + answer.status() + " to the app: " + message(answer.body()));
        }
        return answer;
    }

    private static Map<String, String> headers(Map<String, String> headers) {
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("User-Agent", "RainsCore");
        return headers;
    }

    private static String message(String body) {
        try {
            return JsonParser.parseString(body).getAsJsonObject().get("message").getAsString();
        } catch (RuntimeException notJson) {
            return body == null ? "" : body.length() > 200 ? body.substring(0, 200) : body;
        }
    }

    private static String base64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The key from a PEM file: GitHub hands out PKCS#1 ({@code BEGIN RSA PRIVATE KEY}), which the JDK does not read
     * by itself, so it is wrapped into PKCS#8 here; a PKCS#8 file is read as it is.
     */
    public static PrivateKey readKey(String pem) throws GeneralSecurityException {
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        if (pkcs1) {
            byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01,
                    0x01, 0x01, 0x05, 0x00};
            byte[] version = {0x02, 0x01, 0x00};
            byte[] octets = tagged(0x04, der);
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            inner.writeBytes(version);
            inner.writeBytes(algorithm);
            inner.writeBytes(octets);
            der = tagged(0x30, inner.toByteArray());
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static byte[] tagged(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else if (length < 0x10000) {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length & 0xFF);
        } else {
            out.write(0x83);
            out.write(length >> 16);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static Transport http() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (method, url, headers, body) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach(request::header);
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            try {
                HttpResponse<String> answer = client.send(request.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new Response(answer.statusCode(), answer.body());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", interrupted);
            }
        };
    }
}
