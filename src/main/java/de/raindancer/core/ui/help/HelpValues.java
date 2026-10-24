package de.raindancer.core.ui.help;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/** How a setting's value reads, compares and formats on a help page. */
public final class HelpValues {

    private HelpValues() {
    }

    /** "on"/"off", whole numbers without ".0", lists joined, enum constants as words. */
    public static String display(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean flag) {
            return flag ? "on" : "off";
        }
        if (value instanceof Collection<?> items) {
            return String.join(", ", items.stream().map(HelpValues::display).toList());
        }
        if (value instanceof Enum<?> constant) {
            return constant.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        if (value instanceof Number number) {
            return number(number);
        }
        return String.valueOf(value);
    }

    /** Whether a value counts as "yes" for {@code when:} — on, non-zero, non-empty. */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof Collection<?> items) {
            return !items.isEmpty();
        }
        String text = display(value).strip().toLowerCase(Locale.ROOT);
        return !(text.isEmpty() || text.equals("false") || text.equals("off") || text.equals("no")
                || text.equals("none") || text.equals("0"));
    }

    /** The value as a number, when it is one or reads as one. */
    public static Optional<Double> numeric(Object value) {
        if (value instanceof Number number) {
            return Optional.of(number.doubleValue());
        }
        if (value instanceof Boolean || value == null || value instanceof Collection<?>) {
            return Optional.empty();
        }
        try {
            return Optional.of(Double.parseDouble(display(value).strip().replace(",", "")));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    static String number(Number number) {
        double value = number.doubleValue();
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return String.format(Locale.ROOT, "%,d", (long) value);
        }
        String text = String.format(Locale.ROOT, "%,.2f", value);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /**
     * Seconds as a reader says them: "45 seconds", "1 minute 30 seconds", "2 hours", "3 days 4 hours".
     * Two units at most — "1 day 2 hours 3 minutes 4 seconds" is precise and nobody reads it.
     */
    public static String duration(long seconds) {
        if (seconds <= 0) {
            return "no time at all";
        }
        long[] sizes = {86_400, 3_600, 60, 1};
        String[] names = {"day", "hour", "minute", "second"};
        StringBuilder said = new StringBuilder();
        int units = 0;
        long left = seconds;
        for (int index = 0; index < sizes.length && units < 2; index++) {
            long count = left / sizes[index];
            if (count > 0) {
                if (!said.isEmpty()) {
                    said.append(' ');
                }
                said.append(String.format(Locale.ROOT, "%,d", count)).append(' ').append(names[index])
                        .append(count == 1 ? "" : "s");
                left -= count * sizes[index];
                units++;
            } else if (units > 0) {
                // "1 hour 5 seconds" skips the minutes and reads as a typo; one unit is the honest answer.
                break;
            }
        }
        return said.toString();
    }
}
