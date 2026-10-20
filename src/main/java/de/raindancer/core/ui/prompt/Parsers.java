package de.raindancer.core.ui.prompt;

import de.raindancer.core.platform.util.Times;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The answers a plugin usually asks for, each refusing with a sentence that says what would work.
 * Every one trims what was typed; none throws.
 */
public final class Parsers {

    private Parsers() {
    }

    /** Turns typed text into a value, or says why not. */
    @FunctionalInterface
    public interface Parser<T> {

        Parsed<T> parse(String typed);

        /** The same answer, then a further rule on the value. */
        default Parser<T> andCheck(Predicate<T> rule, String problem) {
            return typed -> {
                Parsed<T> first = parse(typed);
                return first.isOk() && !rule.test(first.value()) ? Parsed.no(problem) : first;
            };
        }

        /** The same answer turned into something else. */
        default <R> Parser<R> map(Function<T, R> into) {
            return typed -> {
                Parsed<T> first = parse(typed);
                return first.isOk() ? Parsed.ok(into.apply(first.value())) : Parsed.no(first.problem());
            };
        }
    }

    /** Any text, up to {@code maxLength} characters, not blank. */
    public static Parser<String> text(int maxLength) {
        return typed -> {
            String clean = typed == null ? "" : typed.strip();
            if (clean.isEmpty()) {
                return Parsed.no("Type something — or say cancel.");
            }
            if (clean.length() > maxLength) {
                return Parsed.no("At most " + maxLength + " characters; that was " + clean.length() + ".");
            }
            return Parsed.ok(clean);
        };
    }

    /** A name: letters, digits, - and _, 1 to {@code maxLength} long — a home, a team, a warp. */
    public static Parser<String> name(int maxLength) {
        Pattern allowed = Pattern.compile("[A-Za-z0-9_-]{1," + Math.max(1, maxLength) + "}");
        return typed -> {
            String clean = typed == null ? "" : typed.strip();
            return allowed.matcher(clean).matches() ? Parsed.ok(clean)
                    : Parsed.no("A name of up to " + maxLength + " letters, digits, - or _.");
        };
    }

    /** A whole number from {@code min} to {@code max}. */
    public static Parser<Integer> wholeNumber(int min, int max) {
        String range = "a whole number from " + min + " to " + max;
        return typed -> {
            try {
                int value = Integer.parseInt(typed == null ? "" : typed.strip());
                return value < min || value > max ? Parsed.no("Not in range — " + range + ".") : Parsed.ok(value);
            } catch (NumberFormatException notANumber) {
                return Parsed.no("That is not " + range + ".");
            }
        };
    }

    /** A number, decimals allowed, from {@code min} to {@code max}. A comma works as the decimal point. */
    public static Parser<Double> number(double min, double max) {
        String range = "a number from " + trim(min) + " to " + trim(max);
        return typed -> {
            try {
                double value = Double.parseDouble((typed == null ? "" : typed.strip()).replace(',', '.'));
                if (Double.isNaN(value) || value < min || value > max) {
                    return Parsed.no("Not in range — " + range + ".");
                }
                return Parsed.ok(value);
            } catch (NumberFormatException notANumber) {
                return Parsed.no("That is not " + range + ".");
            }
        };
    }

    /**
     * An amount of money as the currency reads it — {@code 12}, {@code 12.50}, {@code 1.5k}. Every module asking
     * for a price or a sum asks this way, so an amount means the same in every window.
     *
     * @param zero whether 0 is an answer — "none", "stop selling" — rather than a mistake
     * @param most the highest that may be typed; {@link de.raindancer.core.social.economy.Money#ZERO} for no limit
     */
    public static Parser<de.raindancer.core.social.economy.Money> money(
            de.raindancer.core.social.economy.Currency currency, boolean zero,
            de.raindancer.core.social.economy.Money most) {
        String example = zero ? "an amount like 12, 12.50 or 1.5k — or 0 for none" : "an amount like 12, 12.50 or 1.5k";
        return typed -> {
            var read = currency.parse(typed == null ? "" : typed.strip());
            if (read.isEmpty() || read.get().isNegative() || (!zero && !read.get().isPositive())) {
                return Parsed.no("That is not " + example + ".");
            }
            if (most.isPositive() && read.get().compareTo(most) > 0) {
                return Parsed.no("At most " + currency.format(most) + ".");
            }
            return Parsed.ok(read.get());
        };
    }

    /** Yes or no, in the ways people say it. */
    public static Parser<Boolean> yesNo() {
        return typed -> switch ((typed == null ? "" : typed.strip()).toLowerCase(Locale.ROOT)) {
            case "yes", "y", "true", "on", "ja", "j", "1" -> Parsed.ok(true);
            case "no", "n", "false", "off", "nein", "0" -> Parsed.ok(false);
            default -> Parsed.no("Yes or no.");
        };
    }

    /** A length of time — 30s, 5m, 2h30m, 7d. */
    public static Parser<Duration> duration(Duration shortest, Duration longest) {
        return typed -> {
            Optional<Duration> read = Times.parseLenient(typed);
            if (read.isEmpty()) {
                return Parsed.no("A length like 30s, 5m, 2h30m or 7d.");
            }
            Duration value = read.get();
            boolean tooShort = shortest != null && value.compareTo(shortest) < 0;
            boolean tooLong = longest != null && value.compareTo(longest) > 0;
            if (tooShort || tooLong) {
                if (shortest != null && longest != null) {
                    return Parsed.no("Between " + Times.describe(shortest) + " and " + Times.describe(longest) + ".");
                }
                return Parsed.no(tooShort ? "At least " + Times.describe(shortest) + "."
                        : "At most " + Times.describe(longest) + ".");
            }
            return Parsed.ok(value);
        };
    }

    /**
     * One of these, regardless of case; a unique start is enough ("dia" for "diamond"). The refusal lists
     * them when there are few, or the closest when there are many.
     */
    public static Parser<String> oneOf(Collection<String> choices) {
        List<String> options = List.copyOf(choices);
        return typed -> {
            String clean = (typed == null ? "" : typed.strip()).toLowerCase(Locale.ROOT);
            for (String option : options) {
                if (option.toLowerCase(Locale.ROOT).equals(clean)) {
                    return Parsed.ok(option);
                }
            }
            List<String> starting = new ArrayList<>();
            for (String option : options) {
                if (!clean.isEmpty() && option.toLowerCase(Locale.ROOT).startsWith(clean)) {
                    starting.add(option);
                }
            }
            if (starting.size() == 1) {
                return Parsed.ok(starting.getFirst());
            }
            List<String> shown = starting.isEmpty() ? options : starting;
            String listed = String.join(", ", shown.subList(0, Math.min(8, shown.size())))
                    + (shown.size() > 8 ? ", …" : "");
            return Parsed.no(starting.size() > 1 ? "Which one: " + listed + "?" : "One of: " + listed + ".");
        };
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
