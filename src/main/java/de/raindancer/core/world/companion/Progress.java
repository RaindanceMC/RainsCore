package de.raindancer.core.world.companion;

/** Whether a companion is getting any nearer where it is going — or stuck. */
public final class Progress {

    /** Nearer by less than this is no nearer. */
    private static final double GAIN = 0.5;

    private double best = Double.MAX_VALUE;
    private long since = -1;

    /** Fed how far it still has to go, each tick it is trying; @return no nearer for {@code patience} ticks */
    public boolean stuck(double distance, long tick, int patience) {
        if (since < 0 || distance < best - GAIN) {
            best = Math.min(best, distance);
            since = tick;
            return false;
        }
        return tick - since > patience;
    }

    /** Ticks since it last got nearer. */
    public long stuckFor(long tick) {
        return since < 0 ? 0 : tick - since;
    }

    /** A new goal, or it arrived. */
    public void reset() {
        best = Double.MAX_VALUE;
        since = -1;
    }
}
