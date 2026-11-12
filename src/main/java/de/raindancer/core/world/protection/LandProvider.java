package de.raindancer.core.world.protection;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Whoever knows which piece of ground is where.
 *
 * <p>A plugin that owns regions registers one of these with {@link Land}, and from that moment every other
 * plugin on the server can ask about land without knowing it exists. There is deliberately room for exactly
 * one: two providers each answering for the same block is two sets of rules and no way to say which won.
 *
 * <h2>Why this is an interface and not a store</h2>
 * Because Core storing the regions would mean Core deciding what a region is, and the last attempt at that
 * dragged a claim's pantry, bank and entry fee into the foundation. Here Core holds the question and the
 * enforcement; the shape of the answer belongs to whoever has the data.
 */
public interface LandProvider {

    /** A name for the log line that says who is answering. */
    String name();

    /** The area covering this spot, if any. Called several times a tick — must be cheap. */
    Optional<ProtectedArea> at(Location location);

    /**
     * The area a player is considered to be in, which is not always the one under their feet.
     *
     * <p>A raw lookup flickers between two answers when somebody stands on their own border or on their own
     * roof, and every flicker is an entry and an exit. A provider that tracks presence should answer from
     * that tracking; one that does not can simply delegate to {@link #at}.
     */
    default Optional<ProtectedArea> around(Player player) {
        return player == null ? Optional.empty() : at(player.getLocation());
    }

    /**
     * Whether anything at all is protected in this world.
     *
     * <p>The question asked before something irreversible — regenerating a farm world, pasting over a
     * region. A provider that cannot answer cheaply should still answer honestly rather than guess: the
     * caller is about to delete something.
     */
    boolean hasAnyIn(World world);

    /**
     * Puts a rectangle of ground, the full height of the world, under the server's own protection — a
     * server claim, for a claims plugin. Holding a name already held moves it there.
     *
     * <p>For a plugin that places something the server owns (a teleport point, a pad) and wants it kept
     * safe without knowing who keeps the claims. Corners arrive sorted.
     *
     * @return empty when it is held, otherwise why not, readable
     */
    default Optional<String> holdForServer(World world, String name, int x1, int z1, int x2, int z2) {
        return Optional.of(name() + " cannot hold ground for the server");
    }

    /** Gives back ground held with {@link #holdForServer}. @return whether anything was held under that name */
    default boolean releaseForServer(String name) {
        return false;
    }
}
