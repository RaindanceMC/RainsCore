package de.raindancer.core.platform.util;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * How long somebody has to wait before doing something again.
 *
 * <h2>Why this is a class rather than a map in each feature</h2>
 * Because it had been written five times before this existed — in warps, twice in the teleport
 * requests, in homes and in item abilities — and every copy is a chance to make the same mistake:
 *
 * <pre>{@code
 * Long last = lastUsed.get(player);                 // read
 * if (last != null && now - last < wait) return false;
 * lastUsed.put(player, now);                        // …and write
 * }</pre>
 *
 * <p>Two requests arriving together both see the old value and both are allowed. That is a
 * double-click, a macro, or two Folia region threads getting a free go past the cooldown, and it is
 * invisible until somebody notices they can warp twice. Here the check and the record are one
 * {@link Map#compute} — see {@link #tryUse}.
 *
 * <h2>Asking and using are different questions</h2>
 * A screen greying a button asks {@link #isReady}, which records nothing. Opening a menu must not
 * put somebody on cooldown for a warp they never took. Only {@link #tryUse} and {@link #start}
 * record, and {@code tryUse} records only when it says yes — so a refusal never restarts the wait,
 * which would otherwise mean somebody clicking a greyed button never gets through at all.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. The map is concurrent and the cooldown itself is volatile, so an owner
 * changing it in the settings takes effect for whoever is already waiting rather than at the next
 * restart.
 *
 * @param <K> what is being kept waiting — a player's {@code UUID}, usually, but a warp's name or a
 *            block position works the same way
 */
public final class Cooldowns<K> {

    /** When each key last went. */
    private final Map<K, Long> lastUsed = new ConcurrentHashMap<>();
    /** Milliseconds; injected so a cooldown can be tested without anything sleeping. */
    private final LongSupplier clock;

    private volatile Duration between;
    /** Told of every go recorded — how a cooldown that follows the player is written down. */
    private final java.util.function.BiConsumer<K, Long> recorded;

    /** With the system clock, which is what production wants. */
    public Cooldowns() {
        this(System::currentTimeMillis);
    }

    /** @param clock milliseconds, and only ever asked for — never set */
    public Cooldowns(LongSupplier clock) {
        this(clock, null);
    }

    /** @param recorded told of every go recorded, after it is; null for nobody */
    public Cooldowns(LongSupplier clock, java.util.function.BiConsumer<K, Long> recorded) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.recorded = recorded;
    }

    /**
     * A wait on players that follows them from server to server on a network — {@code /back}, {@code /home}:
     * anything a player could otherwise dodge by switching servers. On a server of its own, a plain one.
     *
     * @param name what it is the wait for, unique across plugins ({@code "tpa:back"})
     */
    public static Cooldowns<java.util.UUID> shared(String name) {
        return de.raindancer.core.platform.network.NetworkCooldowns.installed()
                .map(network -> network.of(name, System::currentTimeMillis))
                .orElseGet(Cooldowns::new);
    }

    /** Takes in a go recorded elsewhere — a later one wins. For a cooldown that follows the player. */
    public void restore(K who, long lastUsedAt) {
        if (who != null) {
            lastUsed.merge(who, lastUsedAt, Math::max);
        }
    }

    // ------------------------------------------------------------------------ how long

    /**
     * How long between one key's goes.
     *
     * <p>Null, zero and a negative duration all mean "no cooldown". Zero deliberately does not mean
     * "an instant one": a feature switched off in the settings writes zero, and a cooldown of zero
     * milliseconds that still filled the map would grow by an entry per player for something nobody
     * is being made to wait for.
     */
    public void every(Duration wait) {
        this.between = wait == null || wait.isZero() || wait.isNegative() ? null : wait;
    }

    /** The wait, or empty when there is none. */
    public Optional<Duration> every() {
        return Optional.ofNullable(between);
    }

    /** Whether anybody is being made to wait at all. */
    public boolean isOn() {
        return between != null;
    }

    // ------------------------------------------------------------------------ asking

    /**
     * Whether this key may go right now, recording it if so.
     *
     * <p>The check and the record are one operation, which is the whole point of the class. The
     * answer is carried out on a flag rather than read back from what {@code compute} returned: with
     * a clock that has not moved between two calls, the value kept and the value written are the
     * same number, so comparing them cannot tell "allowed" from "refused". The first version of this
     * fix did exactly that and let every second go through.
     *
     * @return true when they may, and their wait has now started
     */
    public boolean tryUse(K who) {
        Duration wait = between;
        if (wait == null || who == null) {
            return true;
        }
        long now = clock.getAsLong();
        AtomicBoolean allowed = new AtomicBoolean();
        lastUsed.compute(who, (ignored, last) -> {
            if (last != null && now - last < wait.toMillis()) {
                return last;
            }
            allowed.set(true);
            return now;
        });
        if (allowed.get() && recorded != null) {
            recorded.accept(who, now);
        }
        return allowed.get();
    }

    /**
     * Whether this key may go, without spending the go.
     *
     * <p>What a screen asks to grey a button, and what a command asks before doing the expensive
     * part. Records nothing.
     */
    public boolean isReady(K who) {
        return remaining(who).isEmpty();
    }

    /**
     * How long is left, or empty when nothing is.
     *
     * <p>Empty rather than {@link Duration#ZERO} when it is over, so a message built from this
     * cannot come to read "you can go again in 0s".
     */
    public Optional<Duration> remaining(K who) {
        Duration wait = between;
        Long last = who == null ? null : lastUsed.get(who);
        if (wait == null || last == null) {
            return Optional.empty();
        }
        long left = wait.toMillis() - (clock.getAsLong() - last);
        return left <= 0 ? Optional.empty() : Optional.of(Duration.ofMillis(left));
    }

    // ------------------------------------------------------------------------ recording

    /**
     * Starts the wait without asking first.
     *
     * <p>For the caller that has already decided: a teleport where the asking happens before the
     * player is moved and the recording has to happen after it arrived, so a failed arrival does not
     * cost thirty seconds.
     */
    public void start(K who) {
        if (who == null || between == null) {
            return;
        }
        long now = clock.getAsLong();
        lastUsed.put(who, now);
        if (recorded != null) {
            recorded.accept(who, now);
        }
    }

    /** Forgets one key's wait. Called when a player logs out. */
    public void forget(K who) {
        if (who != null) {
            lastUsed.remove(who);
        }
    }

    /** Forgets everybody. */
    public void clear() {
        lastUsed.clear();
    }

    /**
     * Drops every wait that is already over.
     *
     * <p>{@link #forget} on quit is the real bound on this map. This is the insurance for the
     * entries it misses — somebody who was never seen to leave, on a server that has been up for a
     * month — and it is cheap enough to call from an hourly task.
     *
     * @return how many were dropped
     */
    public int sweep() {
        Duration wait = between;
        if (wait == null) {
            int had = lastUsed.size();
            lastUsed.clear();
            return had;
        }
        long now = clock.getAsLong();
        int before = lastUsed.size();
        lastUsed.values().removeIf(last -> now - last >= wait.toMillis());
        return before - lastUsed.size();
    }

    /** How many keys are being remembered, for a diagnostic and for the tests. */
    public int tracked() {
        return lastUsed.size();
    }
}
