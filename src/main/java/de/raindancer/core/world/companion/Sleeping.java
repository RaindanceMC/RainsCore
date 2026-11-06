package de.raindancer.core.world.companion;

import java.util.List;

/** Whether a bed the game refused for monsters nearby may be slept in after all. */
final class Sleeping {

    /** The box vanilla searches for monsters round a bed: eight blocks across, five up and down. */
    static final double ACROSS = 8;
    static final double UP = 5;

    private Sleeping() {
    }

    /** @param companions for every monster near the bed, whether it is somebody's companion */
    static boolean letIn(List<Boolean> companions) {
        return !companions.isEmpty() && companions.stream().allMatch(Boolean::booleanValue);
    }
}
