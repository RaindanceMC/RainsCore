package de.raindancer.core.moderation.vanish;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Being properly not here.
 *
 * <h2>Why this is Core's</h2>
 * Because vanish is not one feature — it is a promise every other feature has to keep. Somebody
 * hidden who still appears in the tablist, still counts in "3 players online", or whose join message
 * went out anyway is not hidden, and each of those belongs to a different subsystem. Only the thing
 * that owns the tablist, the chat and the player list can make the promise hold.
 *
 * <p>The practical consequence for a plugin is one line: ask {@link #visibleOf} instead of
 * {@code Bukkit.getOnlinePlayers()}, and {@link #isVanished} before mentioning anybody. Nine plugins
 * each keeping their own set is nine chances for five of them to forget.
 *
 * <h2>Why the extras are optional</h2>
 * Flight, gamemode and the rest come apart in practice: somebody may want to be invisible without
 * flying, or to look at a build in creative without being hidden. Bundling them means the one you
 * did not want comes along too, and turning it off afterwards is what leaves a moderator stuck in
 * survival at bedrock. Flight is remembered as it was and put back exactly as it was found.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread.
 */
public final class Vanish {

    private static final LogChannel log = Log.of("vanish");

    private final VanishSink sink;

    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
    private final Set<UUID> maySee = ConcurrentHashMap.newKeySet();
    /** Who could already fly before they vanished, so they are not grounded when they come back. */
    private final Set<UUID> couldAlreadyFly = ConcurrentHashMap.newKeySet();
    /**
     * Who was told to fake a departure on the way in — asked again on {@link #reveal}, rather than
     * read off {@link #fakeDeparture} at that later moment. The flag is server-wide and can change
     * between the two calls; a caller who vanished somebody silently must get a silent reveal back,
     * whatever the flag says by then.
     */
    private final Set<UUID> departureWasFaked = ConcurrentHashMap.newKeySet();

    private volatile boolean flightWhileVanished = true;

    /** Permissions whose holders count as staff for {@link #staffSeeStaff}: whoever may vanish, usually. */
    private final Set<String> staffNodes = ConcurrentHashMap.newKeySet();
    private volatile boolean staffSeeStaff = true;

    /** See {@link #fakeDeparture(boolean)}. On, because being noticed is the thing vanish avoids. */
    private volatile boolean fakeDeparture = true;

    public Vanish(VanishSink sink) {
        this.sink = sink;
    }

    // ---------------------------------------------------------------------------- settings

    /**
     * Whether staff — holders of a {@link #countAsStaff} permission — see vanished players without the
     * separate see-vanished permission. On by default: two vanished moderators should not be invisible
     * to each other.
     */
    public void staffSeeStaff(boolean on) {
        this.staffSeeStaff = on;
    }

    public boolean isStaffSeeStaff() {
        return staffSeeStaff;
    }

    /** Holders of {@code permission} count as staff; a moderation plugin passes the node that lets one vanish. */
    public void countAsStaff(String permission) {
        if (permission != null && !permission.isBlank()) {
            staffNodes.add(permission);
        }
    }

    public Set<String> staffNodes() {
        return Set.copyOf(staffNodes);
    }

    /**
     * Whether vanishing also grants flight.
     *
     * <p>On by default, because somebody who is invisible and walking on the ground is somebody who
     * still opens doors, still triggers pressure plates and trapdoors, and still leaves the block
     * they are standing on lit up by whatever redstone it wires to — none of which {@link #vanish}'s
     * own silencing touches, because none of it is a sound the <em>player</em> makes. Flying over all
     * of it is the simplest way to stop triggering it in the first place. Off for a server that would
     * rather keep the two apart.
     */
    public void flightWhileVanished(boolean granted) {
        this.flightWhileVanished = granted;
    }

    public boolean isFlightWhileVanished() {
        return flightWhileVanished;
    }

    // ---------------------------------------------------------------------------- going

    /** Hides somebody. Answers whether this changed anything. */
    public boolean vanish(UUID who) {
        return vanish(who, false);
    }

    /**
     * Hides somebody, remembering whether they could already fly.
     *
     * @param couldFlyAlready whether flight was already theirs — a creative builder, or somebody
     *                        with the permission. Passing this stops {@link #reveal} taking away
     *                        something it never gave, which is how a builder lands in the void.
     */
    public boolean vanish(UUID who, boolean couldFlyAlready) {
        return vanish(who, couldFlyAlready, fakeDeparture);
    }

    /**
     * The same, deciding for itself whether this particular hiding pretends to be a departure —
     * overriding {@link #fakeDeparture(boolean)}'s server-wide default for this one call.
     *
     * <p>For somebody who is not leaving in any sense a departure message would be honest about: an
     * eliminated tribute stays connected and stays a spectator on the same server, and a "left the
     * game" line about them would be exactly the kind of confident wrong answer this class exists to
     * avoid. Staff vanish keeps using the two-argument form, which still means what the server has
     * configured.
     */
    public boolean vanish(UUID who, boolean couldFlyAlready, boolean announceAsDeparture) {
        if (who == null || !hidden.add(who)) {
            // Already hidden. Re-hiding re-sends packets to every player on the server for nothing.
            return false;
        }
        if (couldFlyAlready) {
            couldAlreadyFly.add(who);
        }
        sink.hide(who, Set.copyOf(maySee));
        sink.collidable(who, false);
        sink.silent(who, true);
        sink.silentJoinLeave(who, true);
        if (flightWhileVanished && (!couldFlyAlready || sink.leasesFlight())) {
            sink.allowFlight(who, true);
        }
        if (announceAsDeparture) {
            // So vanishing looks exactly like logging off. Without it, everybody watching sees a
            // player simply stop existing mid-sentence, which is a louder signal than a leave message.
            sink.announceDeparture(who, Set.copyOf(maySee));
            departureWasFaked.add(who);
        }
        log.info("{} vanished.", who);
        changed(who, true);
        return true;
    }

    /** Brings somebody back. Answers whether they were hidden. */
    public boolean reveal(UUID who) {
        if (who == null || !hidden.remove(who)) {
            return false;
        }
        sink.show(who);
        sink.collidable(who, true);
        sink.silent(who, false);
        sink.silentJoinLeave(who, false);
        if (flightWhileVanished && (!couldAlreadyFly.remove(who) || sink.leasesFlight())) {
            // Only what was granted is taken back. A creative-mode builder who vanished must not
            // land in the void when they return.
            sink.allowFlight(who, false);
        }
        if (departureWasFaked.remove(who)) {
            // The other half, and not optional if the first half happened: a moderator who "left" and
            // then reappears without ever "joining" is a moderator everybody works out was hiding.
            sink.announceArrival(who, Set.copyOf(maySee));
        }
        log.info("{} is visible again.", who);
        changed(who, false);
        return true;
    }

    private final java.util.List<java.util.function.BiConsumer<UUID, Boolean>> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Told whenever somebody vanishes ({@code true}) or reappears — on the thread that did it. */
    public void onChange(java.util.function.BiConsumer<UUID, Boolean> listener) {
        listeners.add(listener);
    }

    private void changed(UUID who, boolean vanished) {
        for (var listener : listeners) {
            try {
                listener.accept(who, vanished);
            } catch (RuntimeException failed) {
                log.error(failed, "A vanish listener failed for {}.", who);
            }
        }
    }

    /**
     * Whether vanishing pretends to be a real departure.
     *
     * <p>On by default: the point of hiding is not being noticed, and a player who simply stops existing
     * mid-conversation is more conspicuous than one who left. A server that would rather say nothing at
     * all can switch it off.
     */
    public void fakeDeparture(boolean fake) {
        this.fakeDeparture = fake;
    }

    public boolean isFakingDeparture() {
        return fakeDeparture;
    }

    /** Hides or reveals. Answers whether they are now hidden. */
    public boolean toggle(UUID who) {
        return isVanished(who) ? !reveal(who) : vanish(who);
    }

    /** Brings everybody back — for a shutdown. Answers how many. */
    public int revealEverybody() {
        int count = 0;
        for (UUID who : List.copyOf(hidden)) {
            if (reveal(who)) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------------------- asking

    public boolean isVanished(UUID who) {
        return who != null && hidden.contains(who);
    }

    /** Everybody hidden. */
    public Set<UUID> everybodyVanished() {
        return Set.copyOf(hidden);
    }

    /**
     * The ones a plugin should treat as online.
     *
     * <p>The call to make instead of {@code getOnlinePlayers()}. Nearly every place vanish leaks is
     * a place that skipped it.
     */
    public List<UUID> visibleOf(Collection<UUID> players) {
        return players == null ? List.of()
                : players.stream().filter(who -> !isVanished(who)).toList();
    }

    /** How many of these count as online. */
    public int countOf(Collection<UUID> players) {
        return visibleOf(players).size();
    }

    /**
     * Whether one player can see another.
     *
     * <p>Somebody can always see themselves — a moderator who cannot has been made to disappear
     * rather than hidden — and anybody allowed to see hidden players can see all of them, so staff
     * do not spend the night walking into each other.
     */
    public boolean canSee(UUID viewer, UUID target) {
        if (viewer == null || target == null) {
            return false;
        }
        return viewer.equals(target) || !isVanished(target) || maySeeVanished(viewer);
    }

    /** Whether somebody is allowed to see hidden players. */
    public boolean maySeeVanished(UUID who) {
        return who != null && maySee.contains(who);
    }

    /** Says whether somebody may see hidden players — from a permission, usually, on join. */
    public void maySeeVanished(UUID who, boolean may) {
        if (who == null) {
            return;
        }
        boolean changed = may ? maySee.add(who) : maySee.remove(who);
        if (!changed) {
            return;
        }
        // Applied now: the cache alone changes nothing a player can see until the next join, which
        // is a moderator granted the node mid-session still seeing nobody.
        for (UUID target : everybodyVanished()) {
            if (!target.equals(who)) {
                sink.seenBy(who, target, may);
            }
        }
    }

    // ---------------------------------------------------------------------------- leaving

    /**
     * Forgets what was only true for this visit, keeping whether they are hidden.
     *
     * <p>Called when somebody leaves. Being hidden deliberately survives: a moderator who reconnects
     * and is suddenly visible has been given away by the plugin that was hiding them.
     */
    public void forgetSession(UUID who) {
        if (who != null) {
            maySee.remove(who);
        }
    }

    /** Forgets somebody entirely — for a player removed from the server. */
    public void forget(UUID who) {
        if (who != null) {
            hidden.remove(who);
            maySee.remove(who);
            couldAlreadyFly.remove(who);
            departureWasFaked.remove(who);
        }
    }
}
