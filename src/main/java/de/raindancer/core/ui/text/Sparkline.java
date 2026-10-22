package de.raindancer.core.ui.text;

/** A row of block bars (▁ to █) for a few numbers in chat — a ping or a tick time over the last minutes. */
public final class Sparkline {

    private static final String BARS = "▁▂▃▄▅▆▇█";

    private Sparkline() {
    }

    /** Lowest value is the lowest bar, highest the highest; a negative value is a gap. */
    public static String of(int[] values) {
        return of(values, 1);
    }

    /**
     * The same, but the bars never span less than {@code smallestSpan}: 36 to 39 ms is flat, not a mountain range.
     */
    public static String of(int[] values, int smallestSpan) {
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int value : values) {
            if (value >= 0) {
                low = Math.min(low, value);
                high = Math.max(high, value);
            }
        }
        long span = Math.max(Math.max(1, smallestSpan), (long) high - low);
        StringBuilder line = new StringBuilder(values.length);
        for (int value : values) {
            if (value < 0) {
                line.append(' ');
            } else {
                int level = (int) Math.min(BARS.length() - 1, (value - low) * (BARS.length() - 1L) / span);
                line.append(BARS.charAt(level));
            }
        }
        return line.toString();
    }
}
