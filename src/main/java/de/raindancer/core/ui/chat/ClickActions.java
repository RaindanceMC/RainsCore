package de.raindancer.core.ui.chat;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Server-side actions behind clickable chat buttons.
 *
 * <h2>Why this exists</h2>
 * A chat component cannot call code. All it can ask the client to do is <em>type a command</em>, so
 * a button that does something on the server needs a command to point at — and both of the obvious
 * ways to arrange that are bad:
 *
 * <ul>
 *   <li><b>A command per feature.</b> {@code /townaccept <id>}, {@code /claimfeeaccept <id>},
 *       {@code /tpaccept}, … Every button anybody ever adds becomes a command in the server's help,
 *       in tab completion, and in the list of things a player can type wrong.</li>
 *   <li><b>Arguments in the command text.</b> The player can read their own chat, so a button
 *       carrying {@code /townaccept claim-4821} is an invitation to type {@code claim-4822} and
 *       approve something nobody offered them.</li>
 * </ul>
 *
 * <p>So the action is registered here against an opaque token and, usually, against the one player
 * allowed to click it. The button runs one shared command. Guessing somebody else's button means
 * guessing a random 128-bit token <em>and</em> being the player it was bound to.
 *
 * <h2>Bounded, swept, and forgotten</h2>
 * Tokens expire, are swept on a timer, are dropped when their owner logs out, and the whole registry
 * is capped — a plugin looping over an offer it keeps re-sending must not be able to fill the heap
 * with closures. When the cap is reached the oldest go first, because the newest button is the one
 * the player is looking at.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. Everything is done under this object's monitor, including running the
 * action's bookkeeping — a one-shot button clicked by eight threads at once runs exactly once.
 * The action itself runs outside the lock, so a slow callback cannot block other players' clicks.
 */
public final class ClickActions {

    private static final LogChannel log = Log.of("chat");

    /** How long a button lasts when the caller does not say. Long enough to read, short enough to forget. */
    public static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(15);

    /**
     * The most buttons that may be waiting at once, across the whole server.
     *
     * <p>Each holds a closure, which can hold whatever the plugin captured — a claim, a town, a
     * whole inventory. Generous enough that a busy server never notices, small enough that a plugin
     * in a loop costs megabytes rather than the process.
     */
    public static final int MAX_PENDING = 10_000;

    /** 16 bytes, url-safe, unpadded: 22 characters that are not worth guessing. */
    private static final int TOKEN_BYTES = 16;

    private record Pending(UUID onlyFor, boolean oneShot, long expiresAt, Consumer<UUID> action,
                           boolean spent) {

        Pending spend() {
            return new Pending(onlyFor, oneShot, expiresAt, action, true);
        }
    }

    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();

    /**
     * Insertion-ordered, so eviction can take the oldest without sorting.
     *
     * <p>Access order would be wrong here: the oldest <em>issued</em> button is the stalest offer,
     * whether or not somebody has looked at it.
     */
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    private record Expiry(long expiresAt, String token) { }

    /**
     * Tokens by expiry, soonest first, so a full registry drops what has lapsed without scanning
     * everything. Entries are removed lazily: one whose token is gone from {@link #pending} is
     * skipped when it surfaces, and the heap is rebuilt when it fills with such ghosts.
     */
    private final PriorityQueue<Expiry> byExpiry =
            new PriorityQueue<>((a, b) -> Long.compare(a.expiresAt(), b.expiresAt()));

    private boolean warnedAboutEviction;

    public ClickActions(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Registers one action and returns the token that runs it.
     *
     * @param onlyFor   the only player allowed to click, or null for a button anybody may use —
     *                  one shared token for a whole broadcast (a welcome button) costs one entry,
     *                  not one per recipient
     * @param validFor  how long it lasts; null means {@link #DEFAULT_LIFETIME}
     * @param oneShot   whether the first click uses it up — true for anything that answers a
     *                  question, so double-clicking [Accept] cannot accept twice
     * @param action    what to do, given whoever clicked
     * @return the token, or null when there was no action to register
     */
    public String register(UUID onlyFor, Duration validFor, boolean oneShot,
                           Consumer<UUID> action) {
        if (action == null) {
            return null;
        }
        Duration lifetime = validFor == null || validFor.isZero() || validFor.isNegative()
                ? DEFAULT_LIFETIME
                : validFor;
        String token = newToken();
        synchronized (this) {
            evictIfFull();
            long expiresAt = clock.getAsLong() + lifetime.toMillis();
            pending.put(token, new Pending(onlyFor, oneShot, expiresAt, action, false));
            byExpiry.add(new Expiry(expiresAt, token));
            compactIfGhostly();
        }
        return token;
    }

    /**
     * Runs the action behind a token, if this player may.
     *
     * <p>The bookkeeping — is it known, is it theirs, is it live, is it spent, mark it spent — all
     * happens under the lock, so a client sending eight clicks at once still runs it once. The
     * action itself runs outside the lock: it is a plugin's code and may be slow, and holding the
     * registry while it runs would make one plugin's button block everybody else's.
     */
    public ClickResult run(UUID clicker, String token) {
        if (clicker == null || token == null || token.isBlank()) {
            return ClickResult.UNKNOWN;
        }
        Consumer<UUID> action;
        synchronized (this) {
            Pending found = pending.get(token);
            if (found == null) {
                return ClickResult.UNKNOWN;
            }
            if (found.onlyFor() != null && !found.onlyFor().equals(clicker)) {
                // Deliberately does not consume it: a stranger clicking must not spend the button
                // the owner has not answered yet.
                return ClickResult.NOT_YOURS;
            }
            if (clock.getAsLong() >= found.expiresAt()) {
                pending.remove(token);
                return ClickResult.EXPIRED;
            }
            if (found.spent()) {
                return ClickResult.SPENT;
            }
            if (found.oneShot()) {
                // Marked spent rather than removed, so the second click can say "you already
                // answered that" instead of "that button has expired".
                pending.put(token, found.spend());
            }
            action = found.action();
        }
        try {
            action.accept(clicker);
            return ClickResult.RAN;
        } catch (RuntimeException failure) {
            // Still spent. A button whose action throws is not a button to offer again — the world
            // it was about has usually moved on, which is often why it threw.
            log.error(failure, "A chat button clicked by {} failed.", clicker);
            return ClickResult.FAILED;
        }
    }

    /** Drops a button a plugin no longer means — an offer that was withdrawn. */
    public synchronized void revoke(String token) {
        if (token != null) {
            pending.remove(token);
        }
    }

    /** Drops every button bound to this player. Called when they log out. */
    public synchronized void forget(UUID player) {
        if (player == null) {
            return;
        }
        // Only theirs: a button nobody was bound to is not this player's to take away.
        pending.values().removeIf(each -> player.equals(each.onlyFor()));
    }

    /** Removes what has expired. Called on a slow timer. */
    public synchronized void sweep() {
        dropExpired();
    }

    /** How many buttons are waiting. For a diagnostic command, and for the tests. */
    public synchronized int size() {
        return pending.size();
    }

    // -------------------------------------------------------------------------- internals

    /** Makes room for one more, sweeping first and only then evicting the oldest. */
    private void evictIfFull() {
        if (pending.size() < MAX_PENDING) {
            return;
        }
        dropExpired();
        if (pending.size() < MAX_PENDING) {
            return;
        }
        Iterator<String> oldestFirst = pending.keySet().iterator();
        while (pending.size() >= MAX_PENDING && oldestFirst.hasNext()) {
            oldestFirst.next();
            oldestFirst.remove();
        }
        if (!warnedAboutEviction) {
            warnedAboutEviction = true;
            log.warn("Chat button registry is full ({} live buttons): the oldest live buttons are being "
                    + "evicted, so offers may stop working. A plugin is probably registering a button per "
                    + "recipient; share one token instead.", MAX_PENDING);
        }
    }

    /** Pops everything that has lapsed off the expiry heap and out of the map. */
    private void dropExpired() {
        long now = clock.getAsLong();
        Expiry head;
        while ((head = byExpiry.peek()) != null && now >= head.expiresAt()) {
            byExpiry.poll();
            Pending found = pending.get(head.token());
            if (found != null && now >= found.expiresAt()) {
                pending.remove(head.token());
            }
        }
    }

    private void compactIfGhostly() {
        if (byExpiry.size() > 2 * pending.size() + 1024) {
            byExpiry.clear();
            pending.forEach((token, each) -> byExpiry.add(new Expiry(each.expiresAt(), token)));
        }
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
