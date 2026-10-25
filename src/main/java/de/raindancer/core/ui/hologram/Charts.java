package de.raindancer.core.ui.hologram;

import java.util.function.DoubleFunction;

/** Charts drawn in text for screens: a bar per column, a meter, and the colours that say how well it goes. */
public final class Charts {

    private static final char[] BARS = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};
    private static final int METER = 10;

    private Charts() {
    }

    /**
     * A bar per value between {@code min} and {@code max}, each coloured by {@code colour}; NaN is a gap. MiniMessage,
     * with one colour tag per run of the same colour.
     */
    public static String spark(double[] values, double min, double max, DoubleFunction<String> colour) {
        StringBuilder out = new StringBuilder();
        String current = null;
        double span = max - min <= 0 ? 1 : max - min;
        for (double value : values) {
            if (Double.isNaN(value)) {
                out.append(' ');
                continue;
            }
            String wanted = colour.apply(value);
            if (!wanted.equals(current)) {
                out.append(wanted);
                current = wanted;
            }
            double share = Math.max(0, Math.min(1, (value - min) / span));
            out.append(BARS[(int) Math.round(share * (BARS.length - 1))]);
        }
        return out.toString();
    }

    /** Ten squares, filled to {@code share} (0..1), the filled ones in {@code colour}. */
    public static String meter(double share, String colour) {
        int done = Double.isNaN(share) ? 0 : (int) Math.round(Math.max(0, Math.min(1, share)) * METER);
        return colour + "■".repeat(done) + "<dark_gray>" + "□".repeat(METER - done);
    }

    public static String tpsColour(double tps) {
        return tps >= 19 ? "<green>" : tps >= 15 ? "<yellow>" : "<red>";
    }

    /** For a load in percent: memory, processor. */
    public static String loadColour(double percent) {
        return percent < 70 ? "<green>" : percent < 90 ? "<yellow>" : "<red>";
    }

    /** The bars and squares alone, without colours — for tests and plain logs. */
    public static String plain(String drawn) {
        return drawn.replaceAll("<[^>]+>", "");
    }
}
