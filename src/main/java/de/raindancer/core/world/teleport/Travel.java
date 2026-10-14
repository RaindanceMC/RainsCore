package de.raindancer.core.world.teleport;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.safety.Safety;
import de.raindancer.core.world.safety.Spot;
import io.papermc.paper.entity.Leashable;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sending a player somewhere: the waiting, the checking and the moving.
 *
 * <h2>Why this is Core's</h2>
 * Because it had been written twice already — in the teleport requests and in homes — and the two
 * copies were identical down to the helper that decides whether somebody has moved. Warps make it
 * three, and three copies of "stand still for three seconds" is three places to fix the next bug in
 * one of. {@link Departures} holds the rules, which is the half a test can reach; this is the half
 * that talks to Bukkit.
 *
 * <h2>What it takes care of, and what it leaves alone</h2>
 * It owns the countdown, the cancelling, finding somewhere safe and the teleport itself. It words
 * nothing: every message goes through a {@link TravelWatcher}, because "Going home in 3…" and
 * "Warping to spawn in 3…" belong to the plugin, and a library that wrote them would be a library
 * deciding what the server sounds like.
 *
 * <p>It also does not listen for anything. {@link TravelListener} does that, and the host registers
 * it — a library that registered its own listeners would be a library whose behaviour a plugin
 * cannot switch off.
 *
 * <h2>Threads</h2>
 * The countdown runs on the traveller's own scheduler, which on Folia is the region thread that owns
 * them. The safety check is asynchronous and its answer arrives on whichever thread finished the
 * chunk load, so everything after it hops back onto the player before touching anything. Nothing
 * here ever blocks on {@code Safety}: joining that future on the server thread is a deadlock the
 * watchdog eventually kills, with nothing in the log to say why.
 */
public final class Travel {

    private static final LogChannel log = Log.of("travel");

    /** What a repeating tick is worth: one second. */
    private static final long A_SECOND_IN_TICKS = 20L;

    /** How often the waiting particles are drawn, and how far away they can be seen. */
    private static final long SHIMMER_EVERY_TICKS = 2L;
    private static final double SHIMMER_RANGE = 32;

    /** Everything about one journey, in one entry — three maps keyed alike is three to get wrong. */
    /**
     * @param groundwork the search for somewhere safe, started at departure rather than on arrival —
     *                   see {@link #prepare}. Null when there is nothing to prepare
     */
    /** @param elsewhere what ends a trip that does not end in a teleport here; null for one that does */
    private record Journey(Location destination, Trip trip, TravelWatcher watcher,
                           ScheduledTask countdown,
                           CompletableFuture<Optional<Spot>> groundwork,
                           java.util.function.Consumer<Player> elsewhere) {
    }

    private final Plugin plugin;
    private final Safety safety;
    private final Audit audit;
    private final Departures departures = new Departures();
    private final Map<UUID, Journey> journeys = new ConcurrentHashMap<>();
    /**
     * Who has a journey in progress at all — warm-up or not.
     *
     * <p>{@link Departures} cannot serve for this: a trip with no warm-up never becomes a departure,
     * so two instant trips issued in the same tick both used to pass the "already travelling" check
     * and both teleported. Worse, a caller that charges its cooldown on arrival saw neither of them
     * as in progress and let a player warp as fast as they could type.
     *
     * <p>Entered with {@code add}, which answers whether it was already there — one atomic operation,
     * because on Folia two commands really can arrive on two threads.
     */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    /**
     * Where each traveller came from, so something can offer them the way back.
     *
     * <p>Recorded here rather than by each caller, which is the whole reason it is Core's: it used to
     * live in the teleport-request plugin, so a home teleport recorded nothing and {@code /back} after
     * {@code /home} took somebody to wherever their last teleport <em>request</em> had been from.
     *
     * <p>Whether {@code /back} exists at all is still the plugin's decision. Recording costs one map
     * entry per player who has travelled, and a plugin that never offers the way back simply never
     * asks.
     */
    private final Returns returns = new Returns();

    /**
     * @param safety where a safe arrival is worked out; null means every trip goes to its exact
     *               coordinates, which is what a server without Core's chunk holds gets
     */
    public Travel(Plugin plugin, Safety safety) {
        this(plugin, safety, null);
    }

    /**
     * @param safety where a safe arrival is worked out; null means every trip goes to its exact
     *               coordinates, which is what a server without Core's chunk holds gets
     * @param audit  where a completed trip is written down; null records nothing, which is what a
     *               caller that has not been given {@code core.audit()} gets
     */
    public Travel(Plugin plugin, Safety safety, Audit audit) {
        this.plugin = plugin;
        this.safety = safety;
        this.audit = audit;
    }

    /** Who is part-way through going somewhere — what a listener and a diagnostic ask. */
    public Departures pending() {
        return departures;
    }

    /**
     * Where each traveller came from.
     *
     * <p>What a {@code /back} is built on. Every arrival through this class records one, so a plugin
     * that offers the way back gets warps, homes and teleport requests for free rather than only its
     * own.
     */
    public Returns cameFrom() {
        return returns;
    }

    // ------------------------------------------------------------------------ setting off

    /**
     * Sends somebody somewhere, after however long the trip says.
     *
     * <p>With no warm-up they go at once. With one, they must stay on the block they are standing on
     * until it is up; {@link TravelListener} is what notices they have not.
     */
    public void go(Player traveller, Location destination, Trip trip, TravelWatcher watcher) {
        begin(traveller, destination, trip, watcher, null);
    }

    /**
     * A trip that does not end in a teleport here — a hop to another server of the network: the same warm-up,
     * standing still and cancelling as {@link #go}, and then {@code away} instead of the teleport. No way back is
     * recorded; the watcher hears {@code arrived} with where they stood.
     */
    public void goElsewhere(Player traveller, Trip trip, TravelWatcher watcher, java.util.function.Consumer<Player> away) {
        begin(traveller, null, trip, watcher, away);
    }

    private void begin(Player traveller, Location destination, Trip trip, TravelWatcher watcher,
                       java.util.function.Consumer<Player> away) {
        if (traveller == null || trip == null) {
            return;
        }
        UUID who = traveller.getUniqueId();

        // One journey at a time, and the check is the entry. Two instant trips issued in the same
        // tick used to both go through — the second finished after the first, so the player ended up
        // wherever the slower one pointed, and any caller charging its cooldown on arrival never saw
        // the first one in progress at all.
        if (!inFlight.add(who)) {
            (watcher == null ? new TravelWatcher() {
            } : watcher).refused(traveller, TravelReason.ALREADY_TRAVELLING, trip);
            return;
        }
        // Wrapped so that every way out of a journey — arriving, being cancelled, being refused —
        // takes the player out of the set. A journey that ends without doing so is a player who can
        // never travel again until they log out.
        TravelWatcher told = new Finishing(who, watcher == null ? new TravelWatcher() {
        } : watcher);

        if (away == null && (destination == null || destination.getWorld() == null)) {
            // Not an error and not a reason to delete anything: a multiverse server unloads worlds
            // for maintenance, and the place works again when the world comes back.
            told.refused(traveller, TravelReason.WORLD_MISSING, trip);
            return;
        }
        if (!trip.hasWarmup()) {
            // Nothing to prepare during: there is no warm-up to do it in.
            departing(traveller, trip);
            if (away != null) {
                leave(traveller, trip, told, away);
            } else {
                arrive(traveller, destination, trip, told, null);
            }
            return;
        }

        Spot standingOn = spotOf(traveller.getLocation());
        if (departures.begin(who, standingOn, trip.warmupSeconds(), trip.what()).isEmpty()) {
            // Belt and braces on the set above, and cheap. A departure that could not be begun is
            // one somebody else is already waiting out.
            told.refused(traveller, TravelReason.ALREADY_TRAVELLING, trip);
            return;
        }
        told.counting(traveller, trip.warmupSeconds(), trip);
        departing(traveller, trip);
        ding(traveller, trip, trip.warmupSeconds());

        // The player's own scheduler: on Folia that is the region thread that owns them, and it
        // follows them if they cross into another region while they wait.
        ScheduledTask countdown = Scheduling.entityTimer(plugin, traveller, A_SECOND_IN_TICKS,
                A_SECOND_IN_TICKS, task -> onASecondPassing(traveller, task),
                // Retired: the player logged out or died mid-wait and the scheduler dropped the
                // task. Without this the departure stays in the map for ever.
                () -> forget(traveller.getUniqueId()));

        if (countdown == null) {
            // Nothing to count with — the plugin is being disabled, most likely. Better to refuse
            // than to leave somebody standing still for a teleport that will never come.
            departures.cancel(who);
            told.refused(traveller, TravelReason.CANNOT_SCHEDULE, trip);
            return;
        }
        // The warm-up is dead time the player is already standing through. Spending it on the work the
        // arrival would otherwise do is what turns "you are being teleported" followed by four seconds of
        // nothing into an arrival that happens when it says it does.
        journeys.put(who, away != null
                ? new Journey(null, trip, told, countdown, null, away)
                : new Journey(destination.clone(), trip, told, countdown, prepare(destination, trip), null));
        shimmer(traveller, trip);

        // The window this closes: between begin() above and the put() on the line before, a movement
        // or a quit on another thread finds the departure and cancels it, but finds no journey — so
        // the task is not cancelled and the player is never told. Without this the task survives up
        // to a second before noticing, and the person standing still is told nothing at all.
        if (!departures.isLeaving(who)) {
            Journey orphan = journeys.remove(who);
            if (orphan != null) {
                orphan.countdown().cancel();
                orphan.watcher().cancelled(traveller, TravelReason.MOVED, trip);
            }
        }
    }

    /**
     * Starts the arrival's groundwork while the traveller is still standing still.
     *
     * <h2>Why this exists</h2>
     * Finding somewhere safe means loading — and, at a scattered farm-world arrival, <em>generating</em> —
     * the chunks around the destination. Done on arrival, that is several seconds between the countdown
     * saying zero and the player actually moving, with nothing on screen to explain the gap. Reported as
     * exactly that: "it said I was being teleported, then it took another four seconds."
     *
     * <p>So it is started at departure instead. The warm-up is time the player is already required to stand
     * through, and this is the one useful thing that can be done with it. By the time the countdown finishes
     * the terrain is usually generated and held, and the teleport is immediate.
     *
     * <p>Nothing depends on it having finished — {@code arrive} waits on the same future either way, so a
     * warm-up shorter than the generation takes is simply the old behaviour with a head start. And nothing
     * is cancelled if the trip is: the chunks were loaded {@code forAMoment}, so they are released on their
     * own rather than held by a journey that never happened.
     *
     * @return the search in flight, or null when this trip does not need one
     */
    private CompletableFuture<Optional<Spot>> prepare(Location destination, Trip trip) {
        if (safety == null || !trip.safeArrival()) {
            return null;
        }
        try {
            return safety.findSafe(spotOf(destination), trip.searchRadius());
        } catch (RuntimeException couldNotStart) {
            // Never at the cost of the journey. A failure here means the arrival does its own search, which
            // is what it did before this existed.
            log.warn("Could not start the arrival check for {}: {}", trip.what(),
                    couldNotStart.toString());
            return null;
        }
    }

    private void onASecondPassing(Player traveller, ScheduledTask task) {
        UUID who = traveller.getUniqueId();
        Journey journey = journeys.get(who);
        if (journey == null || !traveller.isOnline()) {
            task.cancel();
            forget(who);
            return;
        }
        switch (departures.tick(who)) {
            case WAITING -> departures.pending(who).ifPresent(left -> {
                journey.watcher().counting(traveller, left.secondsLeft(), journey.trip());
                ding(traveller, journey.trip(), left.secondsLeft());
            });
            case ARRIVED -> {
                task.cancel();
                journeys.remove(who);
                if (journey.elsewhere() != null) {
                    leave(traveller, journey.trip(), journey.watcher(), journey.elsewhere());
                } else {
                    arrive(traveller, journey.destination(), journey.trip(), journey.watcher(),
                            journey.groundwork());
                }
            }
            // A stray tick from a task that was already cancelled. Nothing to do but stop.
            case NOTHING_PENDING -> {
                task.cancel();
                journeys.remove(who);
            }
        }
    }

    // ------------------------------------------------------------------------ giving up

    /**
     * Gives up on somebody's warm-up and tells them why.
     *
     * @return true when there was one to give up on, so a caller can stay quiet when there was not
     */
    public boolean cancel(Player traveller, TravelReason why) {
        if (traveller == null) {
            return false;
        }
        UUID who = traveller.getUniqueId();
        if (!departures.cancel(who)) {
            // Nothing to give up on. Deliberately not clearing the in-flight mark here: a teleport
            // already under way is not something a movement event can call off, and clearing it
            // would let a second command through while the first is still in the air.
            return false;
        }
        Journey journey = journeys.remove(who);
        if (journey != null) {
            journey.countdown().cancel();
            // The watcher is the wrapped one, so this is also what lets go of the in-flight mark.
            journey.watcher().cancelled(traveller, why, journey.trip());
        } else {
            inFlight.remove(who);
        }
        return true;
    }

    /**
     * Whether this player is part-way through going somewhere.
     *
     * <p>The set rather than the departures, so a trip with no warm-up counts too — for the caller
     * charging a cooldown when somebody arrives, an instant trip in progress is exactly the state
     * that must not look idle.
     */
    public boolean isTravelling(UUID traveller) {
        return traveller != null && inFlight.contains(traveller);
    }

    /** Where they were standing when they asked, for a listener working out whether they moved. */
    public Optional<Spot> setOffFrom(UUID traveller) {
        return departures.pending(traveller).map(Departure::from);
    }

    /** Forgets a player without telling them anything. Called when they log out. */
    public void forget(UUID traveller) {
        departures.cancel(traveller);
        inFlight.remove(traveller);
        // Deliberately does NOT drop where they came from. A journey ending is not a session ending:
        // this is also called when a countdown task is retired mid-wait, and somebody who was
        // interrupted still wants /back to mean the place they set off from. Forgetting the waypoint
        // is TravelListener's, on quit — see there.
        Journey journey = journeys.remove(traveller);
        if (journey != null) {
            journey.countdown().cancel();
        }
    }

    /** Drops every warm-up. For a plugin being disabled. */
    public void clear() {
        journeys.values().forEach(journey -> journey.countdown().cancel());
        journeys.clear();
        departures.clear();
        inFlight.clear();
        returns.clear();
    }

    /**
     * A watcher that lets go of the player once their journey is over, however it ended.
     *
     * <p>Here rather than at each call site because there are five ways out of {@code go} and the one
     * that forgets to release is a player who can never travel again until they log out.
     */
    private final class Finishing implements TravelWatcher {

        private final UUID who;
        private final TravelWatcher told;

        private Finishing(UUID who, TravelWatcher told) {
            this.who = who;
            this.told = told;
        }

        @Override
        public void counting(Player traveller, int secondsLeft, Trip trip) {
            told.counting(traveller, secondsLeft, trip);
        }

        @Override
        public void arrived(Player traveller, Location where, Trip trip) {
            inFlight.remove(who);
            told.arrived(traveller, where, trip);
        }

        @Override
        public void cancelled(Player traveller, TravelReason why, Trip trip) {
            inFlight.remove(who);
            told.cancelled(traveller, why, trip);
        }

        @Override
        public void refused(Player traveller, TravelReason why, Trip trip) {
            inFlight.remove(who);
            told.refused(traveller, why, trip);
        }
    }

    // ------------------------------------------------------------------------ arriving

    /**
     * The teleport itself.
     *
     * <p>Safe arrival first when the trip asks for one, and a refusal when nowhere within the radius
     * is safe. Deliberately not "fall back to the exact spot": that puts the player in the place
     * already known to be dangerous, which is the whole thing the safety package exists to stop.
     */
    private void leave(Player traveller, Trip trip, TravelWatcher watcher, java.util.function.Consumer<Player> away) {
        try {
            away.accept(traveller);
        } finally {
            watcher.arrived(traveller, traveller.getLocation(), trip);
        }
    }

    private void arrive(Player traveller, Location destination, Trip trip, TravelWatcher watcher,
                        CompletableFuture<Optional<Spot>> groundwork) {
        if (!trip.safeArrival() || safety == null) {
            teleport(traveller, destination, trip, watcher);
            return;
        }
        Spot around = spotOf(destination);
        // The search started at departure when there was a warm-up to start it in; otherwise one now.
        // Usually already complete by here, which is the whole point — thenAccept then runs immediately.
        CompletableFuture<Optional<Spot>> search =
                groundwork != null ? groundwork : safety.findSafe(around, trip.searchRadius());
        search.thenAccept(found ->
                // The answer arrives on whichever thread finished the chunk load. Everything below
                // touches the player, so hop back onto them first.
                onThePlayersThread(traveller, "arriving at " + trip.what(), () -> {
                    if (!traveller.isOnline()) {
                        return;
                    }
                    found.ifPresentOrElse(
                            spot -> teleport(traveller, at(spot, destination), trip, watcher),
                            () -> watcher.refused(traveller, TravelReason.NOWHERE_SAFE, trip));
                })).exceptionally(failure -> {
            log.warn("Could not check whether {} is safe: {}", around, failure.toString());
            onThePlayersThread(traveller, "refusing " + trip.what(), () ->
                    watcher.refused(traveller, TravelReason.COULD_NOT_CHECK, trip));
            return null;
        });
    }

    /**
     * Runs something on the thread that owns this player, and logs whatever it throws.
     *
     * <p>The {@code exceptionally} on the future above does <em>not</em> cover this: the work is
     * handed to a scheduler and runs later, on another thread, long after that stage has completed
     * successfully. Anything a watcher throws — a plugin's own message code, most likely — would
     * surface as an uncaught exception on the entity task looper, where on Folia it can take the
     * region's task queue with it.
     *
     * <p>Caught and logged with what it was doing, because the alternative is a stack trace naming
     * nothing but the scheduler.
     */
    private void onThePlayersThread(Player traveller, String what, Runnable task) {
        Scheduling.entity(plugin, traveller, () -> {
            try {
                task.run();
            } catch (RuntimeException thrown) {
                log.warn("{} failed while {}: {}", traveller.getName(), what, thrown.toString());
            }
        });
    }

    private void teleport(Player traveller, Location destination, Trip trip,
                          TravelWatcher watcher) {
        // Gathered before the player is moved, because afterwards there is nothing standing near them
        // to gather: the dog is still where they were, and they are not there any more.
        List<Companion> travellingWith = companionsOf(traveller, trip);
        // The same reason: where they came from has to be read while they are still standing in it.
        Location cameFrom = traveller.getLocation().clone();

        // Async rather than a region-scheduled synchronous teleport: this is the one that handles
        // another plugin refusing the move — a world border, a closed dimension — without the
        // player being told twice that they have arrived somewhere they have not.
        traveller.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                .whenComplete((moved, failure) -> onThePlayersThread(traveller,
                        "arriving at " + trip.what(), () -> {
                    if (failure != null) {
                        log.warn("Could not send {} to {}: {}", traveller.getName(), trip.what(),
                                failure.toString());
                        watcher.refused(traveller, TravelReason.TELEPORT_REFUSED, trip);
                        return;
                    }
                    if (Boolean.FALSE.equals(moved)) {
                        watcher.refused(traveller, TravelReason.TELEPORT_REFUSED, trip);
                        return;
                    }
                    bring(travellingWith, destination, trip, traveller);
                    // Recorded only on a teleport that actually happened. Recording the intention
                    // would offer somebody the way back from a journey that was refused.
                    returns.remember(traveller.getUniqueId(),
                            Waypoint.of(cameFrom, Waypoint.Cause.TELEPORT,
                                    System.currentTimeMillis()));
                    if (audit != null) {
                        // One line per completed trip, not per attempt: a refused or cancelled
                        // warm-up never reaches here, so the journal says what actually happened
                        // rather than what somebody typed.
                        audit.record(AuditEntry.of("travel", "teleported")
                                .by(traveller.getUniqueId(), traveller.getName())
                                .in(destination.getWorld() == null
                                        ? null : destination.getWorld().getName())
                                .saying("to " + trip.what()));
                    }
                    if (!trip.isQuiet()) {
                        show(traveller, "arrival", show -> {
                            Location landed = traveller.getLocation();
                            show.arrived(traveller.getUniqueId(), landed.getWorld().getName(),
                                    landed.getX(), landed.getY(), landed.getZ());
                        });
                    }
                    watcher.arrived(traveller, destination, trip);
                }));
    }

    // ------------------------------------------------------------------------ how it looks

    /** Core's, when it is running — a Travel built in a test or without Core plays nothing. */
    private static TravelShow show() {
        return de.raindancer.core.RainsCore.isAvailable() ? de.raindancer.core.RainsCore.get().travelShow() : null;
    }

    /** Runs {@code play} against the show, and never lets a sound stop somebody travelling. */
    private static void show(Player traveller, String what, java.util.function.Consumer<TravelShow> play) {
        TravelShow show = show();
        if (show == null) {
            return;
        }
        try {
            play.accept(show);
        } catch (RuntimeException broken) {
            log.warn("The {} effect for {} failed: {}", what, traveller.getName(), broken.toString());
        }
    }

    /** One second of the countdown, heard by the traveller. */
    private static void ding(Player traveller, Trip trip, int secondsLeft) {
        if (!trip.isQuiet()) {
            show(traveller, "countdown", show -> show.counting(traveller.getUniqueId(), secondsLeft));
        }
    }

    private void departing(Player traveller, Trip trip) {
        if (trip.isQuiet()) {
            return;
        }
        show(traveller, "departure", show -> {
            Location at = traveller.getLocation();
            show.departed(traveller.getUniqueId(), at.getWorld().getName(), at.getX(), at.getY(), at.getZ());
        });
    }

    /**
     * Particles around somebody standing still for a teleport, until they leave or stop waiting.
     *
     * <p>Stops itself: it checks the departure every time it draws, so none of the five ways a warm-up
     * ends has to remember to cancel it.
     */
    private void shimmer(Player traveller, Trip trip) {
        TravelShow show = show();
        if (show == null || trip.isQuiet()) {
            return;
        }
        UUID who = traveller.getUniqueId();
        Optional<String> particle;
        de.raindancer.core.ui.effect.ParticleShape shape;
        Integer colour;
        Integer colourTo;
        int density;
        try {
            particle = show.waitParticle(who);
            shape = show.waitShape(who);
            colour = show.waitColour(who).orElse(null);
            colourTo = show.waitColourTo(who).orElse(null);
            density = show.waitDensity(who);
        } catch (RuntimeException broken) {
            log.warn("The waiting particles for {} failed: {}", traveller.getName(), broken.toString());
            return;
        }
        if (particle.isEmpty()) {
            return;
        }
        long[] drawn = {0};
        Scheduling.entityTimer(plugin, traveller, 1L, SHIMMER_EVERY_TICKS, task -> {
            if (!traveller.isOnline() || !departures.isLeaving(who)) {
                task.cancel();
                return;
            }
            de.raindancer.core.ui.effect.ParticleShows.around(traveller, particle.get(), colour, colourTo, density, shape,
                    drawn[0]++, SHIMMER_RANGE, viewer -> true,
                    // Wings live exactly until the next redraw: longer and two frames show at once.
                    shape.isWings() ? (int) SHIMMER_EVERY_TICKS : null);
        });
    }

    // ------------------------------------------------------------------------ what comes along

    /**
     * One thing coming along, and whether it needs re-leashing once it arrives.
     *
     * @param wasOnALead whether this was a genuine lead to the traveller — as opposed to a vehicle or
     *                   a shoulder-passenger, which Paper carries along and re-attaches on its own. See
     *                   {@link #bring}.
     */
    private record Companion(Entity entity, boolean wasOnALead) {
    }

    /**
     * The entities that travel with this player.
     *
     * <p>Read off the running server and handed to {@link Entourage}, which owns the decision and is
     * tested without one. Nothing here decides anything: it converts what Bukkit knows into the plain
     * facts the rule is written against.
     *
     * <p>The vehicle they are riding, and whatever is riding with them, are reported as things they
     * are <em>leading</em>. From the traveller's point of view a boat they are towing and a boat they
     * are sitting in are the same "I am taking this with me", and treating them differently would mean
     * two villagers in a boat came along only when the boat was on a lead.
     */
    private List<Companion> companionsOf(Player traveller, Trip trip) {
        Entourage entourage = new Entourage(trip.companions());
        if (!entourage.isWorthLooking()) {
            return List.of();
        }
        UUID who = traveller.getUniqueId();
        Map<UUID, Entity> byId = new LinkedHashMap<>();
        Set<UUID> onALead = new HashSet<>();
        List<Entourage.Candidate> around = new ArrayList<>();

        // The vehicle and its other passengers, however far the seat is from the eyes.
        Entity vehicle = traveller.getVehicle();
        if (vehicle != null) {
            // The vehicle only. Its other passengers are not considered separately: Paper carries
            // them with it, and teleporting a passenger in its own right is what throws it out of the
            // boat on arrival. Whether the vehicle may travel at all is then one decision — and a
            // vehicle carrying somebody else never may.
            consider(vehicle, who, 0, true, byId, onALead, around);
        }
        // Whatever is riding on the traveller themselves — a parrot, usually. They arrive with the
        // player for the same reason, so this is only here to keep them out of the nearby scan below.
        for (Entity onTheirShoulder : traveller.getPassengers()) {
            byId.put(onTheirShoulder.getUniqueId(), onTheirShoulder);
        }

        // Then everything within reach. The radius is the policy's, and the lead is not limited by it
        // — but something on a lead is by definition within a lead's length, which is shorter.
        int radius = Math.max(trip.companions().radius(), A_LEADS_LENGTH);
        Location standingAt = traveller.getLocation();
        for (Entity nearby : traveller.getNearbyEntities(radius, radius, radius)) {
            // Anything riding something else is left to its vehicle: teleporting a passenger in its
            // own right dismounts it on arrival, which is how two villagers in a towed boat end up
            // swimming.
            if (nearby.getVehicle() != null) {
                continue;
            }
            consider(nearby, who, blocksBetween(standingAt, nearby.getLocation()), false, byId,
                    onALead, around);
        }

        List<Companion> coming = new ArrayList<>();
        for (Entourage.Candidate chosen : entourage.from(around, who)) {
            Entity entity = byId.get(chosen.entity());
            if (entity != null) {
                coming.add(new Companion(entity, onALead.contains(chosen.entity())));
            }
        }
        return coming;
    }

    /**
     * How far apart two places are, in whole blocks.
     *
     * <p>Squared and compared rather than {@code distance()}, which throws when the two are in
     * different worlds. They should not be — {@code getNearbyEntities} only ever returns entities from
     * the player's own world — but "should not be" is how a warp comes to fail with an
     * IllegalArgumentException in somebody's log.
     */
    private static int blocksBetween(Location from, Location to) {
        if (from.getWorld() == null || to.getWorld() == null
                || !from.getWorld().equals(to.getWorld())) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(Math.sqrt(from.distanceSquared(to)));
    }

    /**
     * How long a lead is, at most, so the search cannot be narrower than one.
     *
     * <p>A vanilla lead snaps at ten blocks. Searching only the policy's radius would leave a boat
     * trailing at full length behind, which is exactly the case the feature exists for.
     */
    private static final int A_LEADS_LENGTH = 10;

    private void consider(Entity entity, UUID traveller, int blocksAway, boolean riding,
                          Map<UUID, Entity> byId, Set<UUID> onALead,
                          List<Entourage.Candidate> around) {
        if (entity == null || byId.containsKey(entity.getUniqueId())) {
            return;
        }
        try {
            boolean isPlayer = entity instanceof Player;
            boolean genuinelyLeashed = !riding && isLeashedTo(entity, traveller);
            UUID leadHeldBy = riding ? traveller : (genuinelyLeashed ? traveller : null);
            UUID tamedBy = entity instanceof Tameable tameable && tameable.isTamed()
                            && tameable.getOwner() != null
                    ? tameable.getOwner().getUniqueId()
                    : null;
            boolean isTame = tamedBy != null;
            boolean carriesAPlayer = carriesAPlayer(entity, traveller);

            byId.put(entity.getUniqueId(), entity);
            if (genuinelyLeashed) {
                onALead.add(entity.getUniqueId());
            }
            around.add(new Entourage.Candidate(entity.getUniqueId(), isPlayer, leadHeldBy, tamedBy,
                    blocksAway, isTame, carriesAPlayer));
        } catch (RuntimeException unreadable) {
            // On Folia an entity a few blocks away can belong to another region thread, and reading
            // it from this one is refused rather than merely wrong. Skipped, not fatal: the cost of
            // guessing is a dog left behind, and the cost of throwing is a warp that fails.
            log.warn("Could not read {} to see whether it travels: {}",
                    entity.getType(), unreadable.toString());
        }
    }

    /**
     * Whether somebody other than the traveller is sitting in or on this — at any depth.
     *
     * <p>Recursive because a passenger may itself carry one: a player on a donkey in a boat. Paper
     * moves the whole stack with the vehicle, so a check that only looked one level down would let a
     * towed boat teleport a player after all.
     */
    static boolean carriesAPlayer(Entity entity, UUID traveller) {
        for (Entity passenger : entity.getPassengers()) {
            // The traveller in their own boat is the one person it may carry: counting them as
            // "somebody else" meant the vehicle a player sat in never came along.
            boolean somebodyElse = passenger instanceof Player
                    && !passenger.getUniqueId().equals(traveller);
            if (somebodyElse || carriesAPlayer(passenger, traveller)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this entity is genuinely on a lead held by the traveller, as opposed to riding with
     * them.
     *
     * <p>Checked through {@link Leashable} rather than {@code LivingEntity}: since 1.20.5 a boat or a
     * minecart can carry a lead too, and a mob is not the only thing somebody tows home. A check
     * against {@code LivingEntity} alone is the one a towed boat full of villagers silently fails —
     * the boat is never recognised as led, so it and everyone riding it stays behind.
     */
    private boolean isLeashedTo(Entity entity, UUID traveller) {
        if (!(entity instanceof Leashable leashable) || !leashable.isLeashed()) {
            return false;
        }
        Entity holder = leashable.getLeashHolder();
        return holder != null && holder.getUniqueId().equals(traveller);
    }

    /**
     * Moves whatever is coming along, after the player has arrived.
     *
     * <p>Each on its own scheduler, and each failure logged rather than thrown: on Folia every one of
     * these may belong to a different region, and one animal that cannot be moved must not stop the
     * rest — or, worse, leave the player standing at the destination with nothing and no explanation.
     *
     * <p>Nothing is un-leashed or dismounted first — a vehicle's passengers are carried with it and
     * need no help. But a genuine lead is not: teleporting a leashed entity on its own, holder or not,
     * snaps the lead the moment the entity's new position is more than a lead's length from where the
     * holder is standing, which after a warp it always is. So a companion that was actually on a lead
     * gets put back on one once it lands, rather than trusting Paper to have kept it attached.
     */
    private void bring(List<Companion> travellingWith, Location destination, Trip trip,
                       Player traveller) {
        int placed = 0;
        for (Companion companion : travellingWith) {
            Entity entity = companion.entity();
            boolean reLeash = companion.wasOnALead();
            // Spread around the arrival rather than stacked in one block. Twenty animals in one
            // block is entity cramming, which suffocates them — a feature that brings the dog and
            // then kills it is worse than one that leaves it behind.
            Location spot = besideTheArrival(destination, placed++, travellingWith.size());
            Scheduling.entity(plugin, entity, () -> {
                try {
                    if (spot.getWorld() == null || !spot.isWorldLoaded()) {
                        // The world went between the player's arrival and this task. Nothing to do
                        // but say so: the animal stays where it was, which is recoverable.
                        log.warn("Could not bring {} to {}: that world is gone.",
                                entity.getType(), trip.what());
                        return;
                    }
                    entity.teleportAsync(spot, PlayerTeleportEvent.TeleportCause.PLUGIN)
                            .thenRun(() -> {
                                if (!reLeash) {
                                    return;
                                }
                                // A tick's delay: the leash-break the engine does for an entity now
                                // far from its holder runs as part of settling the move, and re-tying
                                // it in the same instant just has that undo it again.
                                Scheduling.entityLater(plugin, entity, 1L, () ->
                                        reLeash(entity, traveller, trip));
                            });
                } catch (RuntimeException thrown) {
                    log.warn("Could not bring {} to {}: {}", entity.getType(), trip.what(),
                            thrown.toString());
                }
            });
        }
    }

    /** Puts a companion back on its lead after arrival, once, and only if it needs it. */
    private void reLeash(Entity entity, Player traveller, Trip trip) {
        if (!entity.isValid() || !traveller.isOnline()
                || !(entity instanceof Leashable leashable) || leashable.isLeashed()) {
            return;
        }
        try {
            leashable.setLeashHolder(traveller);
        } catch (RuntimeException thrown) {
            log.warn("Could not put {} back on its lead after {}: {}", entity.getType(),
                    trip.what(), thrown.toString());
        }
    }

    /**
     * Somewhere within a block or so of the arrival, for one of several companions.
     *
     * <p>A ring rather than a line, so a boat and a horse do not end up inside each other, and small
     * enough that nothing lands through a wall — the destination has already been checked for safety
     * and a metre away has not, so this stays inside the same block wherever it can.
     */
    private static Location besideTheArrival(Location arrival, int which, int howMany) {
        if (howMany <= 1) {
            return arrival;
        }
        double angle = 2 * Math.PI * which / howMany;
        return arrival.clone().add(Math.cos(angle) * 0.8, 0, Math.sin(angle) * 0.8);
    }

    // ------------------------------------------------------------------------ the two conversions

    /** Where a player's feet are, as the value the rules are written against. */
    public static Spot spotOf(Location where) {
        if (where == null || where.getWorld() == null) {
            return null;
        }
        return new Spot(where.getWorld().getName(), where.getBlockX(), where.getBlockY(),
                where.getBlockZ());
    }

    /**
     * A safe spot, as somewhere to be put.
     *
     * <p>The middle of the block, not its corner — a player put on the corner is a player half
     * inside the block next to it. Facing whichever way the original destination faced, so a warp
     * nudged two blocks by the safety check still looks at what it was pointed at.
     */
    private static Location at(Spot spot, Location facingLike) {
        World world = Bukkit.getWorld(spot.world());
        if (world == null) {
            return facingLike;
        }
        return new Location(world, spot.centreX(), spot.y(), spot.centreZ(),
                facingLike.getYaw(), facingLike.getPitch());
    }
}
