package de.raindancer.core.world.combat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Who has killed how many without dying, and what an announcer would call it: first blood, a double kill, a rampage,
 * a shutdown. Only counts and names — saying it (title, sound, chat) is the caller's, so every game can sound like
 * itself. One instance per round; {@link #reset()} between rounds.
 *
 * <p>Thread safety: every method is synchronized; kills are reported from region threads.
 */
public final class Streaks {

    /** What a kill can be called. A kill may be several at once — a triple kill that is also a rampage. */
    public enum Callout {
        FIRST_BLOOD, DOUBLE_KILL, TRIPLE_KILL, MULTI_KILL, KILLING_SPREE, RAMPAGE, UNSTOPPABLE, GODLIKE, SHUTDOWN;

        /** The streak this name belongs to, or 0 for names that are not about a streak's length. */
        public int streak() {
            return switch (this) {
                case KILLING_SPREE -> 3;
                case RAMPAGE -> 5;
                case UNSTOPPABLE -> 7;
                case GODLIKE -> 10;
                default -> 0;
            };
        }
    }

    /** A kill as counted: the killer's streak after it, and what it is called. */
    public record Kill(UUID killer, int streak, List<Callout> callouts) {
        public Kill {
            callouts = List.copyOf(callouts);
        }
    }

    /** A streak at least this long is worth a shutdown when it ends. */
    public static final int SHUTDOWN_FROM = 3;

    private final LongSupplier clock;
    private final long window;
    private final Map<UUID, Integer> streak = new HashMap<>();
    private final Map<UUID, Integer> best = new HashMap<>();
    private final Map<UUID, Integer> quick = new HashMap<>();
    private final Map<UUID, Long> lastKill = new HashMap<>();
    private boolean anyBlood;

    /** @param multiKillWindow how close together two kills must be to count as a double kill */
    public Streaks(LongSupplier clock, Duration multiKillWindow) {
        this.clock = clock;
        this.window = Math.max(1, multiKillWindow.toMillis());
    }

    public synchronized Kill killed(UUID killer, UUID victim) {
        if (killer.equals(victim)) {
            died(victim);
            return new Kill(killer, 0, List.of());
        }
        List<Callout> callouts = new ArrayList<>();
        if (!anyBlood) {
            anyBlood = true;
            callouts.add(Callout.FIRST_BLOOD);
        }
        if (streak.getOrDefault(victim, 0) >= SHUTDOWN_FROM) {
            callouts.add(Callout.SHUTDOWN);
        }
        died(victim);

        long now = clock.getAsLong();
        Long previous = lastKill.put(killer, now);
        int inARow = previous != null && now - previous <= window ? quick.getOrDefault(killer, 1) + 1 : 1;
        quick.put(killer, inARow);
        if (inARow == 2) {
            callouts.add(Callout.DOUBLE_KILL);
        } else if (inARow == 3) {
            callouts.add(Callout.TRIPLE_KILL);
        } else if (inARow > 3) {
            callouts.add(Callout.MULTI_KILL);
        }

        int length = streak.merge(killer, 1, Integer::sum);
        best.merge(killer, length, Math::max);
        for (Callout callout : Callout.values()) {
            if (callout.streak() == length) {
                callouts.add(callout);
            }
        }
        return new Kill(killer, length, callouts);
    }

    /** Died with nobody to credit — the void, a fall, their own arrow. */
    public synchronized void died(UUID victim) {
        streak.remove(victim);
        quick.remove(victim);
        lastKill.remove(victim);
    }

    public synchronized int streakOf(UUID player) {
        return streak.getOrDefault(player, 0);
    }

    /** The longest streak this player had since the last reset, ended or not. */
    public synchronized int bestOf(UUID player) {
        return best.getOrDefault(player, 0);
    }

    public synchronized void forget(UUID player) {
        died(player);
    }

    public synchronized void reset() {
        streak.clear();
        best.clear();
        quick.clear();
        lastKill.clear();
        anyBlood = false;
    }
}
