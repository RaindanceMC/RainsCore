package de.raindancer.core.moderation.whitelist;

import java.util.Locale;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * What a join code looks like — handed out at the proxy's door or on a server's kick screen, typed into Discord's
 * {@code /link}. Read off a screen, so no 0/O and 1/I/L, and long enough that guessing is pointless:
 * 31^10 ≈ 8·10^14, against five wrong tries per Discord account per ten minutes.
 *
 * <p>Only the JDK: RainsNetworkProxy shades this one class, so the proxy and the bot agree on every code.
 */
public final class JoinCode {

    public static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int LENGTH = 10;

    private JoinCode() {
    }

    public static String newCode(RandomGenerator random) {
        StringBuilder code = new StringBuilder(LENGTH + 1);
        for (int i = 0; i < LENGTH; i++) {
            if (i == LENGTH / 2) {
                code.append('-');
            }
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** "abcde 12345", "ABCDE-12345 " → "ABCDE-12345"; anything that can't be a code → empty. */
    public static Optional<String> normalise(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String letters = typed.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (letters.length() != LENGTH || !letters.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0)) {
            return Optional.empty();
        }
        return Optional.of(letters.substring(0, LENGTH / 2) + "-" + letters.substring(LENGTH / 2));
    }
}
