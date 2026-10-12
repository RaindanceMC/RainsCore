package de.raindancer.core.platform.link;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Signing with the shared secret: an HMAC over a time, a nonce and the body.
 *
 * <ul>
 *   <li>A request cannot be forged or changed on the way — the signature covers all of it.</li>
 *   <li>It cannot be replayed: one older than {@link #MOST_SKEW_MILLIS} is refused, and inside that window the host
 *       remembers every nonce it has accepted.</li>
 *   <li>An answer is signed too, over the nonce of the request it answers — so a guest believes only what the real
 *       host said to <em>this</em> request, not whatever is listening on the port, and not an old answer replayed.</li>
 * </ul>
 */
public final class LinkAuth {

    public static final String TIME = "X-Link-Time";
    public static final String NONCE = "X-Link-Nonce";
    public static final String SIGNATURE = "X-Link-Signature";

    /** How far apart two clocks on the same machine (or LAN) may be, and how long a signature is good for. */
    public static final long MOST_SKEW_MILLIS = 30_000;

    /** Shorter than this and the secret is guessable; it is a password between two servers. */
    public static final int SHORTEST_SECRET = 24;

    private static final SecureRandom RANDOM = new SecureRandom();

    private LinkAuth() {
    }

    public static void requireStrong(String secret) {
        if (secret == null || secret.length() < SHORTEST_SECRET) {
            throw new IllegalArgumentException("a link secret needs at least " + SHORTEST_SECRET + " characters");
        }
    }

    public static String nonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** A request's signature. */
    public static String sign(String secret, long time, String nonce, String body) {
        return hmac(secret, "request", Long.toString(time), nonce, body);
    }

    /** An answer's signature, tied to the request it answers. */
    public static String signAnswer(String secret, long time, String requestNonce, String body) {
        return hmac(secret, "answer", Long.toString(time), requestNonce, body);
    }

    /** Whether a time header is a number within the window — checked before a body is even read. */
    public static boolean isFresh(String time, long now) {
        if (time == null) {
            return false;
        }
        try {
            return Math.abs(now - Long.parseLong(time)) <= MOST_SKEW_MILLIS;
        } catch (NumberFormatException garbage) {
            return false;
        }
    }

    public static boolean verify(String secret, String time, String nonce, String signature, String body, long now) {
        if (!isNonce(nonce) || signature == null || !isFresh(time, now)) {
            return false;
        }
        return same(sign(secret, Long.parseLong(time), nonce, body), signature);
    }

    public static boolean verifyAnswer(String secret, String time, String requestNonce, String signature, String body,
                                       long now) {
        if (signature == null || !isFresh(time, now)) {
            return false;
        }
        return same(signAnswer(secret, Long.parseLong(time), requestNonce, body), signature);
    }

    /** 16 to 64 hex digits — anything else in a header could be meant to blur where one signed field ends. */
    static boolean isNonce(String nonce) {
        if (nonce == null || nonce.length() < 16 || nonce.length() > 64) {
            return false;
        }
        for (int at = 0; at < nonce.length(); at++) {
            char c = nonce.charAt(at);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    private static boolean same(String expected, String given) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Each field length-prefixed, the first naming what is signed: no two different lists of fields give the same
     * input, so no request signature is ever an answer signature and no field can be stretched into the next.
     */
    private static String hmac(String secret, String... fields) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            for (String field : fields) {
                byte[] bytes = (field == null ? "" : field).getBytes(StandardCharsets.UTF_8);
                mac.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                mac.update(bytes);
            }
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is part of every JDK", impossible);
        }
    }
}
