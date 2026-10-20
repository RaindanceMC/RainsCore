package de.raindancer.core.moderation.vanish;

import java.util.Set;
import java.util.UUID;

/**
 * The one thing in this package that talks to the server.
 *
 * <p>Who is hidden, who may see them, and what should happen when somebody vanishes is bookkeeping
 * and is tested without a server. Hiding an entity from every player is not.
 */
public interface VanishSink {

    /**
     * Hides this player from everybody except the people named.
     *
     * <p>The exceptions are passed in rather than asked for, so this stays a sink with no opinions
     * and no reference back to {@link Vanish} — which would otherwise have to be constructed before
     * itself.
     *
     * @param mayStillSee who is allowed to keep seeing them
     */
    void hide(UUID who, Set<UUID> mayStillSee);

    /** Shows them again. */
    void show(UUID who);

    /**
     * Shows or hides one hidden player to one viewer — for a viewer whose right to see the hidden
     * changed while both are online. A no-op by default, so a sink written before this still compiles.
     */
    default void seenBy(UUID viewer, UUID who, boolean sees) {
    }

    /** Turns flight on or off. */
    void allowFlight(UUID who, boolean allowed);

    /**
     * Whether {@link #allowFlight} takes and gives back a lease of its own rather than flipping the switch. Then
     * flight they had already is no reason to skip it: that may be a claim's, ending while they are still hidden,
     * and the lease never takes away what was there before it.
     */
    default boolean leasesFlight() {
        return false;
    }

    /** Whether other players bump into them. */
    void collidable(UUID who, boolean collides);

    /**
     * Whether they make a sound.
     *
     * <p>Footsteps, splashing, taking damage, drinking a potion — all of it a fact about the
     * player's own entity, playing whether or not anybody nearby can see who it is coming from. A
     * player who is invisible but still audible is not hidden, only unseen; somebody who hears
     * footsteps stop right beside them and nobody is standing there has learned exactly as much as
     * somebody who saw the moderator outright.
     */
    void silent(UUID who, boolean silent);

    /** Whether their joining and leaving is announced. */
    void silentJoinLeave(UUID who, boolean silent);

    /**
     * Says to the server what it would have said had they actually logged off.
     *
     * <p>The wording is the game's own, not a plugin's — the whole value of the line is that it is
     * indistinguishable from a real departure. A vanished moderator whose disappearance is announced as
     * "X vanished" is a moderator everybody knows is watching.
     *
     * @param exceptThem who should not be told, because they know
     */
    void announceDeparture(UUID who, Set<UUID> exceptThem);

    /** The counterpart: what the server would have said had they just joined. */
    void announceArrival(UUID who, Set<UUID> exceptThem);
}
