package de.raindancer.core.world.teleport;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Where each player was before they were moved — what {@code /back} goes to.
 *
 * <h2>Why this is Core's, and one</h2>
 * "The last place I was" is asked by more than one thing: a warp, a home, a teleport request and a death all move
 * somebody, and all want to be undoable. RainsCore installs one ({@link #installed()}) that every {@link Travel}
 * records into, whichever plugin made it — so {@code /back} after {@code /home} goes where they were before the
 * home, not to the last teleport request.
 *
 * <h2>On a network it follows the player</h2>
 * Kept in the network's database with the server it is on ({@link ReturnsStore}): a player who leaves a server —
 * a hop, {@code /hub}, the proxy moving them — can go back to where they stood there, from any server. A player
 * off the network for a couple of minutes is forgotten, so it still means "where I just was". On a server of its
 * own it is memory only, as it always was: a place from before a restart is not where anybody means.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. {@link #arrived} reads the database: call it off the server's threads.
 */
public final class Returns {

    /** A place to go back to, and the server it is on ({@code ""} on a server of its own). */
    public record Elsewhere(String server, Waypoint where) {
    }

    private static volatile Returns installed;

    private final Map<UUID, Elsewhere> places = new ConcurrentHashMap<>();
    private final ReturnsStore store;
    private final String self;
    private final Executor writer;

    /** Memory only — a server of its own. */
    public Returns() {
        this(null, "", Runnable::run);
    }

    /**
     * @param store  where it is kept on a network
     * @param self   this server's network id
     * @param writer runs the database writes — off the server's threads
     */
    public Returns(ReturnsStore store, String self, Executor writer) {
        this.store = store;
        this.self = self == null ? "" : self;
        this.writer = writer;
    }

    /** RainsCore's, for every {@link Travel} of every plugin. Set once by Core. */
    public static void install(Returns returns) {
        installed = returns;
    }

    public static Optional<Returns> installed() {
        return Optional.ofNullable(installed);
    }

    /**
     * Remembers where somebody was, on this server.
     *
     * <p><b>A teleport cannot overwrite a death.</b> Somebody who died and was then moved still wants {@code /back}
     * to mean their body. Only using it, or dying again, releases that hold — on a network, also when the death
     * was on another server.
     *
     * @return whether it was remembered
     */
    public boolean remember(UUID player, Waypoint where) {
        if (player == null || where == null || !where.isUsable()) {
            return false;
        }
        boolean[] kept = new boolean[1];
        Elsewhere here = new Elsewhere(self, where);
        places.compute(player, (ignored, held) -> {
            if (held != null && held.where().cause() == Waypoint.Cause.DEATH
                    && where.cause() != Waypoint.Cause.DEATH) {
                return held;
            }
            kept[0] = true;
            return here;
        });
        if (kept[0] && store != null) {
            writer.execute(() -> store.save(player, self, where));
        }
        return kept[0];
    }

    /**
     * Puts back a place taken with {@link #takeAnywhere} for a trip that did not happen — on its own server, not
     * this one. A death since then still outranks it.
     */
    public boolean restore(UUID player, Elsewhere place) {
        if (player == null || place == null || !place.where().isUsable()) {
            return false;
        }
        boolean[] kept = new boolean[1];
        places.compute(player, (ignored, held) -> {
            if (held != null && held.where().cause() == Waypoint.Cause.DEATH
                    && place.where().cause() != Waypoint.Cause.DEATH) {
                return held;
            }
            kept[0] = true;
            return place;
        });
        if (kept[0] && store != null) {
            writer.execute(() -> store.save(player, place.server(), place.where()));
        }
        return kept[0];
    }

    /** Where they would go back to on this server, without using it up. */
    public Optional<Waypoint> of(UUID player) {
        return anywhere(player).filter(place -> place.server().equals(self)).map(Elsewhere::where);
    }

    /** Where they would go back to on this server, used up; nothing when it is on another server. */
    public Optional<Waypoint> take(UUID player) {
        return of(player).isPresent() ? takeAnywhere(player).map(Elsewhere::where) : Optional.empty();
    }

    /** Where they would go back to, on whichever server, without using it up. */
    public Optional<Elsewhere> anywhere(UUID player) {
        return player == null ? Optional.empty() : Optional.ofNullable(places.get(player));
    }

    /**
     * Where they would go back to, on whichever server, used up — kept, {@code /back} twice would hop between two
     * places for ever.
     */
    public Optional<Elsewhere> takeAnywhere(UUID player) {
        Optional<Elsewhere> taken = player == null ? Optional.empty() : Optional.ofNullable(places.remove(player));
        if (taken.isPresent() && store != null) {
            writer.execute(() -> store.delete(player));
        }
        return taken;
    }

    /** A player arrived on this server: what they had on the network is read in. Off the server's threads. */
    public Returns arrived(UUID player) {
        if (store != null && player != null) {
            store.load(player).ifPresentOrElse(found -> places.put(player, found), () -> places.remove(player));
        }
        return this;
    }

    /**
     * A player left this server standing at {@code where}: on a network that is where {@code /back} takes them from
     * the next server (a death still outranks it). Forgotten here either way.
     */
    public void left(UUID player, Waypoint where) {
        if (store != null) {
            remember(player, where);
        }
        forget(player);
    }

    /** Forgets a player here — they left. On a network it is kept for wherever they go next. */
    public void forget(UUID player) {
        if (player != null) {
            places.remove(player);
        }
    }

    /** Forgets everybody here. For a plugin being disabled. */
    public void clear() {
        places.clear();
    }

    /** How many are remembered here, for a diagnostic and for the tests. */
    public int tracked() {
        return places.size();
    }

    /** Whether this one is kept on the network. */
    public boolean onTheNetwork() {
        return store != null;
    }
}
