package de.raindancer.core.platform.network;

/**
 * The network's housekeeping — throwing away old log lines, old events, old arrivals — done by one server, not
 * by every one of them against the same tables. Also why it is done by the settings of one server: a group with a
 * shorter chat-log retention must not cut everybody's history short.
 *
 * <p>{@link #tick()} every few seconds keeps the role; the chores themselves ask {@link #ifMine}.
 */
public final class Chores {

    /** The role whose holder does them. */
    public static final String ROLE = "maintenance";

    /** A server of its own: every chore is its own. */
    public static final Chores ALONE = new Chores(null);

    private final NetworkRoles roles;
    private volatile boolean mine;

    public Chores(NetworkRoles roles) {
        this.roles = roles;
        this.mine = roles == null;
    }

    /** Takes or renews the role; call well inside {@link NetworkRoles#LEASE_MILLIS}. */
    public void tick() {
        if (roles != null) {
            mine = roles.claim(ROLE);
        }
    }

    public boolean isMine() {
        return mine;
    }

    public void ifMine(Runnable chore) {
        if (mine) {
            chore.run();
        }
    }

    /** Lets another server take over at once — at a clean shutdown. */
    public void release() {
        if (roles != null && mine) {
            mine = false;
            roles.release(ROLE);
        }
    }
}
