package de.raindancer.core.world.activity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Whole blocks travelled since last asked, from the game's own centimetre counters; the rest carries over. */
public final class TravelMeter {

    private final Map<UUID, Long> counted = new ConcurrentHashMap<>();

    /** @param totalCentimetres the player's walk, sprint, swim and climb statistics added up */
    public long since(UUID player, long totalCentimetres) {
        Long before = counted.get(player);
        if (before == null || totalCentimetres < before) {
            counted.put(player, totalCentimetres);
            return 0;
        }
        long blocks = (totalCentimetres - before) / 100;
        counted.put(player, before + blocks * 100);
        return blocks;
    }

    public void forget(UUID player) {
        counted.remove(player);
    }
}
