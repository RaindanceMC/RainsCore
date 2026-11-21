package de.raindancer.core.content.pack;

/**
 * Whether what is in a player's pack can be used yet, asked again and again after they log on: music from the
 * pack, say, which a client without the pack would not hear. Since 2.115.0.
 */
public final class PackWait {

    public enum Step {
        /** The pack is on. */
        GO,
        /** Still on its way. */
        WAIT,
        /** Refused, failed, or too long: never mind this time. */
        GIVE_UP
    }

    /** How long a pack may take before it is given up on. */
    public static final int PATIENCE_SECONDS = 30;

    private PackWait() {
    }

    public static Step step(PackStatus status, double waitedSeconds) {
        if (status.isWearing()) {
            return Step.GO;
        }
        if (status == PackStatus.DECLINED || status == PackStatus.FAILED || waitedSeconds >= PATIENCE_SECONDS) {
            return Step.GIVE_UP;
        }
        return Step.WAIT;
    }
}
