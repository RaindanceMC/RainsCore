package de.raindancer.core.platform.network;

/**
 * This server's id on its network, for code that holds no reference to Core — a {@code Poi} asked for
 * its location has to know whether its world is this server's world or a namesake on another server.
 *
 * <p>Blank until Core has started, and blank on a server of its own; blank means "everything is here",
 * which is exactly what every 1.x server assumed.
 */
public final class ThisServer {

    private static volatile String id = "";

    private ThisServer() {
    }

    public static String id() {
        return id;
    }

    /** Set once by Core at startup. */
    public static void set(String serverId) {
        id = serverId == null ? "" : serverId;
    }

    /** Whether something kept for {@code server} belongs to this one: blank is everybody's. */
    public static boolean is(String server) {
        return server == null || server.isBlank() || id.isBlank() || server.equals(id);
    }
}
