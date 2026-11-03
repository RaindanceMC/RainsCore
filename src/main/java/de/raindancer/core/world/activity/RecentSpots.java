package de.raindancer.core.world.activity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where each player built lately, so placing a block, breaking it and placing it again on the same spot counts once —
 * but building on, block after block, always counts. Only the latest {@code most} spots per player are kept.
 */
public final class RecentSpots {

    private final long forMillis;
    private final int most;
    private final Map<UUID, LinkedHashMap<String, Long>> spots = new ConcurrentHashMap<>();

    public RecentSpots(long forMillis, int most) {
        this.forMillis = forMillis;
        this.most = Math.max(1, most);
    }

    /** Whether this is the first building on that spot by that player within the time; remembers it either way. */
    public boolean firstTime(UUID player, String world, int x, int y, int z, long now) {
        String spot = world + ':' + x + ':' + y + ':' + z;
        LinkedHashMap<String, Long> mine = spots.computeIfAbsent(player, id -> new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > most;
            }
        });
        synchronized (mine) {
            Long before = mine.put(spot, now);
            return before == null || now - before >= forMillis;
        }
    }

    public void forget(UUID player) {
        spots.remove(player);
    }
}
