package de.raindancer.core.ui.tablist;

import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Locale;

/**
 * How the server is keeping up, at one moment: ticks per second (the last minute), milliseconds per tick, and the
 * heap in megabytes.
 */
public record ServerLoad(double tps, double mspt, long usedMb, long maxMb) {

    /** A tick is 50 ms at 20 TPS; past that the server falls behind. */
    public static NamedTextColor msptColour(double mspt) {
        return mspt < 40 ? NamedTextColor.GREEN : mspt < 50 ? NamedTextColor.YELLOW : NamedTextColor.RED;
    }

    public static NamedTextColor tpsColour(double tps) {
        return tps >= 19.5 ? NamedTextColor.GREEN : tps >= 17 ? NamedTextColor.YELLOW : NamedTextColor.RED;
    }

    /** One decimal, never above 20: Paper reports a hair over after a catch-up, which reads like a bug. */
    public String tpsText() {
        return String.format(Locale.ROOT, "%.1f", Math.min(20.0, Math.max(0, tps)));
    }

    public String msptText() {
        return Math.round(Math.max(0, mspt)) + " ms";
    }

    public String ramText() {
        return String.format(Locale.ROOT, "%.1f/%.1f GB", usedMb / 1024.0, maxMb / 1024.0);
    }
}
