package de.raindancer.core.platform.link;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Signing a request with the shared secret: an HMAC over the time and the body, so a request cannot be forged, changed
 * on the way, or replayed later — one older than {@link #MOST_SKEW_MILLIS} is refused.
 */
public final class LinkAuth {

    public static final String TIME = "X-Link-Time";
    public static final String SIGNATURE = "X-Link-Signature";

    /** How far apart two clocks on the same machine (or LAN) may be, and how long a signature is good for. */
    public static final long MOST_SKEW_MILLIS = 30_000;

    /** Shorter than this and the secret is guessable; it is a password between two servers. */
    public static final int SHORTEST_SECRET = 24;

    private LinkAuth() {
    }

    public static void requireStrong(String secret) {
        if (secret == null || secret.length() < SHORTEST_SECRET) {
            throw new IllegalArgumentException("a link secret needs at least " + SHORTEST_SECRET + " characters");
        }
    }

    public static String sign(String secret, long time, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(Long.toString(time).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
            mac.update(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is part of every JDK", impossible);
        }
    }

    public static boolean verify(String secret, String time, String signature, String body, long now) {
        if (time == null || signature == null) {
            return false;
        }
        long sent;
        try {
            sent = Long.parseLong(time);
        } catch (NumberFormatException garbage) {
            return false;
        }
        if (Math.abs(now - sent) > MOST_SKEW_MILLIS) {
            return false;
        }
        byte[] expected = sign(secret, sent, body).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
    }
}
