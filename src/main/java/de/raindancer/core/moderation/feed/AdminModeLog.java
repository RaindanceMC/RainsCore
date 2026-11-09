package de.raindancer.core.moderation.feed;

import de.raindancer.core.moderation.players.AdminModes;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Turns {@link AdminModes} switches into {@link ModerationFeed} notices and measures how long a stay lasted.
 * Admin mode survives a quit, and the plugin running it announces "in" again when somebody joins still in it;
 * that is not a new entry, so a player already counted as in produces no second notice.
 */
public final class AdminModeLog implements AdminModes.Listener {

    private final ModerationFeed feed;
    private final LongSupplier clock;
    private final Map<UUID, Long> since = new ConcurrentHashMap<>();

    public AdminModeLog(ModerationFeed feed, LongSupplier clock) {
        this.feed = feed;
        this.clock = clock;
    }

    @Override
    public void switched(UUID player, boolean inAdminMode) {
        long now = clock.getAsLong();
        if (inAdminMode) {
            if (since.putIfAbsent(player, now) == null) {
                feed.adminMode(new AdminModeNotice(player, true, 0));
            }
            return;
        }
        Long entered = since.remove(player);
        feed.adminMode(new AdminModeNotice(player, false, entered == null ? 0 : Math.max(0, now - entered)));
    }
}
