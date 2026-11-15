package de.raindancer.core.platform.util;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything this jar does with lengths of time: reading them from what an admin typed, and writing
 * them back in words.
 *
 * <h2>Why the two parsers live in the same file</h2>
 * There are deliberately two, and that is the point of putting them here. The moderation commands are
 * <em>strict</em>: {@code 7} is refused, because the difference between seven minutes and seven days is
 * a player kept out for an afternoon and a player kept out for a week, and nothing in the input says
 * which was meant. The claims commands are <em>lenient</em>: they accept {@code 2h30m} and treat a bare
 * number as seconds, which is what their prompts have always told players to type.
 * <p>
 * Both behaviours are wanted. What was not wanted is what the code had before: two parsers in two
 * modules, neither mentioning the other, so nobody reading either one could tell that the same input
 * meant different things depending on which command they typed it into. Here the difference is one
 * scroll apart and named in the method.
 */
public final class Times {

    /** One strict token: an amount and a unit, nothing else. */
    private static final Pattern SINGLE = Pattern.compile("(?i)^(\\d+)\\s*([smhdw])$");

    /** One token of a lenient sequence, so {@code 2h30m} can be read piece by piece. */
    private static final Pattern TOKEN =
            Pattern.compile("(\\d+)\\s*([smhdw])", Pattern.CASE_INSENSITIVE);

    private Times() {
    }

    // ------------------------------------------------------------------ reading

    /**
     * One token, or nothing.
     *
     * @throws IllegalArgumentException for anything else, including a bare number — so a caller cannot
     *                                  mistake "I could not read that" for a length it invented
     */
    public static Duration parseStrict(String raw) {
        Matcher matcher = SINGLE.matcher(raw == null ? "" : raw.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "'" + raw + "' is not a length. Use 30m, 12h, 7d, 2w or perm.");
        }
        try {
            return of(Long.parseLong(matcher.group(1)), matcher.group(2).charAt(0));
        } catch (ArithmeticException tooLong) {
            // NumberFormatException is already an IllegalArgumentException; an overflow is not.
            throw new IllegalArgumentException("'" + raw + "' is longer than any length can be.");
        }
    }

    /**
     * A sequence of tokens, or a bare number of seconds.
     * <p>
     * Empty for anything unreadable, or for a total of zero: a timeout of no time at all is a mistake,
     * not an instruction.
     */
    public static Optional<Duration> parseLenient(String raw) {
        try {
            return lenient(raw);
        } catch (NumberFormatException | ArithmeticException tooLong) {
            // Twenty digits, or ten thousand years: unreadable, which is what this promises to say.
            return Optional.empty();
        }
    }

    private static Optional<Duration> lenient(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String input = raw.trim();
        if (input.matches("\\d+")) {
            return Optional.of(Duration.ofSeconds(Long.parseLong(input)));
        }
        Matcher matcher = TOKEN.matcher(input);
        Duration total = Duration.ZERO;
        int consumed = 0;
        boolean matched = false;
        while (matcher.find()) {
            // Gaps mean the input was not entirely a duration — "2h fish 30m" is a typo, not 2h30m.
            if (matcher.start() != consumed) {
                return Optional.empty();
            }
            consumed = matcher.end();
            matched = true;
            total = total.plus(of(Long.parseLong(matcher.group(1)), matcher.group(2).charAt(0)));
        }
        if (!matched || consumed != input.length() || total.isZero() || total.isNegative()) {
            return Optional.empty();
        }
        return Optional.of(total);
    }

    private static Duration of(long amount, char unit) {
        return switch (Character.toLowerCase(unit)) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            case 'w' -> Duration.ofDays(Math.multiplyExact(amount, 7L));
            // Unreachable through either pattern; a unit added to one regex and not here should shout.
            default -> throw new IllegalArgumentException("Unknown unit '" + unit + "'.");
        };
    }

    /** Whether this is one of the words that mean "no end". */
    public static boolean isPermanent(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        return lower.equals("perm") || lower.equals("permanent") || lower.equals("forever")
                || lower.equals("never");
    }

    // ------------------------------------------------------------------ writing

    /**
     * How long, in a sentence: {@code for 7 days}, {@code permanently}.
     * <p>
     * Rounded to the largest whole unit on purpose. A ban screen saying "for 6 days, 23 hours and 59
     * minutes" is not more useful to the person reading it than "for 7 days".
     */
    public static String describe(Duration duration) {
        if (duration == null) {
            return "permanently";
        }
        long days = duration.toDays();
        if (days >= 7 && days % 7 == 0) {
            long weeks = days / 7;
            return "for " + weeks + (weeks == 1 ? " week" : " weeks");
        }
        if (days > 0) {
            return "for " + days + (days == 1 ? " day" : " days");
        }
        long hours = duration.toHours();
        if (hours > 0) {
            return "for " + hours + (hours == 1 ? " hour" : " hours");
        }
        long minutes = Math.max(1, duration.toMinutes());
        return "for " + minutes + (minutes == 1 ? " minute" : " minutes");
    }

    /** Compact form for a tooltip: {@code 2d 4h}, {@code 15m 30s}. */
    public static String compact(long millis) {
        if (millis <= 0L) {
            return "0s";
        }
        long seconds = millis / 1000L;
        long weeks = seconds / 604_800L;
        seconds %= 604_800L;
        long days = seconds / 86_400L;
        seconds %= 86_400L;
        long hours = seconds / 3_600L;
        seconds %= 3_600L;
        long minutes = seconds / 60L;
        seconds %= 60L;

        StringBuilder builder = new StringBuilder();
        append(builder, weeks, "w");
        append(builder, days, "d");
        append(builder, hours, "h");
        append(builder, minutes, "m");
        // Seconds only when they are the largest unit there is, or the whole thing would read "1w 3s".
        if (builder.isEmpty() || (weeks == 0 && days == 0 && hours == 0)) {
            append(builder, seconds, "s");
        }
        return builder.isEmpty() ? "0s" : builder.toString();
    }

    private static void append(StringBuilder builder, long value, String suffix) {
        if (value <= 0) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(value).append(suffix);
    }

    /** Relative label for a screen: {@code 3d 4h ago}. */
    public static String ago(long epochMillis) {
        long delta = System.currentTimeMillis() - epochMillis;
        return delta <= 0 ? "just now" : compact(delta) + " ago";
    }

    /** What tab completion offers for a length. */
    public static List<String> suggestions() {
        return List.of("perm", "30m", "1h", "12h", "1d", "7d", "2w");
    }
}
