package de.raindancer.core.ui.tree;

import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one pool of skill points a hero has, shared by every plugin that sells something for them. The skills plugin
 * {@linkplain #provide provides} the pool (level, total, what its own trees took); any other plugin whose unlocks cost
 * those points registers a {@linkplain #spender spender} that says what a player has spent there. What is left is
 * the total less everyone's spending, so a point is never spent twice. Without a pool there are no points.
 * Safe from any thread; spenders are asked often, so they must answer from memory.
 */
public final class SkillPoints {

    /** The plugin that hands out the points. */
    public interface Pool {

        /** The hero level the points come from; also what unlocks of other plugins measure their {@code level:} by. */
        int level(UUID player);

        /** Every point the player has earned. */
        int total(UUID player);

        /** The points spent in the pool owner's own trees. */
        int spent(UUID player);
    }

    /** What a player has spent on points elsewhere. */
    @FunctionalInterface
    public interface Spender {

        int spent(UUID player);
    }

    private record Provided(Plugin owner, Pool pool) {
    }

    private record Entry(Plugin owner, String id, Spender spender) {
    }

    private static volatile Provided provided;
    private static final List<Entry> spenders = new CopyOnWriteArrayList<>();

    private SkillPoints() {
    }

    /** Offers the pool; a later one replaces the earlier. {@code owner} may be null (then it is never auto-forgotten). */
    public static void provide(Plugin owner, Pool pool) {
        provided = new Provided(owner, pool);
    }

    /** Takes the pool away. */
    public static void withdraw() {
        provided = null;
    }

    /** Registers what a plugin has spent; one already there under the same id is replaced. */
    public static synchronized void spender(Plugin owner, String id, Spender spender) {
        spenders.removeIf(entry -> entry.id().equals(id));
        spenders.add(new Entry(owner, id, spender));
    }

    public static synchronized boolean removeSpender(String id) {
        return spenders.removeIf(entry -> entry.id().equals(id));
    }

    public static boolean available() {
        return provided != null;
    }

    /** Why there are no points, for a player. */
    public static String unavailableReason() {
        return "Skill points come from the skills plugin, which is not running here.";
    }

    public static int level(UUID player) {
        Provided p = provided;
        return p == null ? 0 : Math.max(0, p.pool().level(player));
    }

    public static int total(UUID player) {
        Provided p = provided;
        return p == null ? 0 : Math.max(0, p.pool().total(player));
    }

    /** What the spenders registered here have taken; the pool owner's own trees are not in it. */
    public static int spentElsewhere(UUID player) {
        int sum = 0;
        for (Entry entry : spenders) {
            try {
                sum += Math.max(0, entry.spender().spent(player));
            } catch (RuntimeException broken) {
                // a spender that fails must not take everybody's points down with it
            }
        }
        return sum;
    }

    /** Total less the pool owner's own spending less every spender's; never below zero, zero without a pool. */
    public static int left(UUID player) {
        Provided p = provided;
        if (p == null) {
            return 0;
        }
        return Math.max(0, total(player) - Math.max(0, p.pool().spent(player)) - spentElsewhere(player));
    }

    public static int forgetFrom(ClassLoader loader) {
        int dropped = 0;
        Provided p = provided;
        if (p != null && p.owner() != null && p.owner().getClass().getClassLoader() == loader) {
            provided = null;
            dropped++;
        }
        int before = spenders.size();
        spenders.removeIf(entry -> entry.owner() != null && entry.owner().getClass().getClassLoader() == loader);
        return dropped + before - spenders.size();
    }

    public static void clear() {
        provided = null;
        spenders.clear();
    }
}
