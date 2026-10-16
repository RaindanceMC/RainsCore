package de.raindancer.core.world.movement;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Who was launched when, so their landing — and only their next one — is spared. */
public final class SoftLandings {

    private final LongSupplier clock;
    private final Map<UUID, Long> launched = new ConcurrentHashMap<>();

    public SoftLandings(LongSupplier clock) {
        this.clock = clock;
    }

    public void launched(UUID player) {
        launched.put(player, clock.getAsLong());
    }

    /** Whether this landing is spared; asking uses it up. */
    public boolean spared(UUID player) {
        Long at = launched.remove(player);
        return at != null && clock.getAsLong() - at <= JumpPads.SOFT_LANDING_MILLIS;
    }

    public void forget(UUID player) {
        launched.remove(player);
    }
}
