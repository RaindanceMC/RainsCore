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
    private final AdminSessionRecorder recorder;

    public AdminModeLog(ModerationFeed feed, LongSupplier clock) {
        this(feed, clock, null);
    }

    /** With a recorder, each stay is recorded from going in and its recap rides on the way out. */
    public AdminModeLog(ModerationFeed feed, LongSupplier clock, AdminSessionRecorder recorder) {
        this.feed = feed;
        this.clock = clock;
        this.recorder = recorder;
    }

    @Override
    public void switched(UUID player, boolean inAdminMode) {
        long now = clock.getAsLong();
        if (inAdminMode) {
            if (recorder != null) {
                recorder.begin(player);
            }
            if (since.putIfAbsent(player, now) == null) {
                feed.adminMode(new AdminModeNotice(player, true, 0));
            }
            return;
        }
        Long entered = since.remove(player);
        AdminSessionRecap recap = recorder == null ? null : recorder.end(player).orElse(null);
        feed.adminMode(new AdminModeNotice(player, false, entered == null ? 0 : Math.max(0, now - entered), recap));
    }
}
