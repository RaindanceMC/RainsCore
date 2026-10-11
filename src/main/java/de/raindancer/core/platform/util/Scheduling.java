package de.raindancer.core.platform.util;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Thin wrapper around Paper's regionised schedulers.
 * <p>
 * Paper ships {@code RegionScheduler}, {@code AsyncScheduler}, {@code GlobalRegionScheduler} and
 * {@code Entity#getScheduler()} on both vanilla Paper and Folia, so using them exclusively keeps the
 * plugin Folia-safe without any runtime branching. {@code Bukkit.getScheduler()} is never touched.
 *
 * <h2>A disabled plugin</h2>
 * Paper's schedulers refuse every task from a plugin that is not enabled, and {@code onDisable} runs
 * after the plugin has been marked disabled. So every one-shot method here runs its task <em>in
 * place</em> for a disabled plugin, with any failure logged rather than thrown: the cleanup a plugin
 * does on the way out — showing the hidden, releasing chunks, handing items back — is exactly the work
 * that hops threads, and nothing would ever run it later. Shutdown is the one moment nothing else is
 * ticking. Timers are not covered: starting one while disabling is a mistake, and still throws.
 */
public final class Scheduling {

    private static final LogChannel log = Log.of("scheduling");

    private static final boolean FOLIA = classPresent("io.papermc.paper.threadedregions.RegionizedServer");

    private Scheduling() {
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    /** Whether this plugin may still schedule — false from the moment it starts disabling. */
    public static boolean isLive(Plugin plugin) {
        return plugin != null && plugin.isEnabled();
    }

    /** Runs a task for a disabled plugin where it is, keeping a failure inside it. */
    private static boolean ranInPlace(Plugin plugin, Runnable task) {
        if (isLive(plugin)) {
            return false;
        }
        try {
            task.run();
        } catch (RuntimeException | LinkageError failure) {
            log.warn(failure, "A task from {} failed while it was shutting down.",
                    plugin == null ? "a plugin" : plugin.getName());
        }
        return true;
    }

    /** Runs on the thread owning {@code location}'s region, next tick. */
    public static void region(Plugin plugin, Location location, Runnable task) {
        if (ranInPlace(plugin, task)) {
            return;
        }
        Bukkit.getRegionScheduler().execute(plugin, location, task);
    }

    /** Runs on the thread owning {@code location}'s region, a number of ticks from now. */
    public static void regionLater(Plugin plugin, Location location, long delayTicks, Runnable task) {
        if (ranInPlace(plugin, task)) {
            return;
        }
        Bukkit.getRegionScheduler().runDelayed(plugin, location, ignored -> task.run(), Math.max(1L, delayTicks));
    }

    /** Runs on the thread owning {@code entity}; silently drops the task if the entity is removed. */
    public static void entity(Plugin plugin, Entity entity, Runnable task) {
        if (ranInPlace(plugin, task)) {
            return;
        }
        entity.getScheduler().run(plugin, ignored -> task.run(), null);
    }

    /**
     * Runs on the thread owning {@code entity}, and runs {@code retired} instead when the entity is gone before
     * that could happen — a player who disconnected in between.
     *
     * <p>For work that must end one way or the other, like handing somebody items that were taken from
     * somewhere else a moment ago: {@link #entity(Plugin, Entity, Runnable)} drops the task in that case, and
     * with it whatever the task was going to do. {@code retired} runs on whichever thread notices, so it must not
     * touch the world.
     */
    public static void entity(Plugin plugin, Entity entity, Runnable task, Runnable retired) {
        if (ranInPlace(plugin, task)) {
            return;
        }
        if (entity.getScheduler().run(plugin, ignored -> task.run(), retired) == null) {
            retired.run();
        }
    }

    /**
     * Runs on the thread owning {@code entity}: right here when that is this thread — always, on
     * Paper's main thread — and through the entity's scheduler otherwise. For an action that should
     * take effect at once where it can, and must not touch somebody another Folia region is ticking.
     */
    public static void onOwner(Plugin plugin, Entity entity, Runnable task) {
        if (entity == null) {
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            task.run();
            return;
        }
        entity(plugin, entity, task);
    }

    /**
     * The same, a number of ticks later.
     * <p>
     * Needed where the point is to read state back <em>after</em> the server has finished changing it —
     * an inventory edit is applied by Minecraft after the click event returns, so anything that reads
     * the result has to wait a tick. Relying on {@link #entity} being "next tick anyway" would be
     * relying on a detail of the scheduler rather than saying what is meant.
     */
    public static void entityLater(Plugin plugin, Entity entity, long delayTicks, Runnable task) {
        if (ranInPlace(plugin, task)) {
            return;
        }
        entity.getScheduler().runDelayed(plugin, ignored -> task.run(), null, Math.max(1L, delayTicks));
    }

    /** Repeating task pinned to the region owning {@code location}. */
    public static ScheduledTask regionTimer(Plugin plugin, Location location, long delayTicks,
                                            long periodTicks, Consumer<ScheduledTask> task) {
        return Bukkit.getRegionScheduler().runAtFixedRate(plugin, location, task,
                Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    /** Repeating task pinned to {@code entity}; automatically cancelled when the entity is removed. */
    public static ScheduledTask entityTimer(Plugin plugin, Entity entity, long delayTicks,
                                            long periodTicks, Consumer<ScheduledTask> task) {
        return entityTimer(plugin, entity, delayTicks, periodTicks, task, null);
    }

    /**
     * Repeating task pinned to {@code entity}, with a callback for when the entity goes away.
     * <p>
     * The retired callback matters more than it looks: a player's entity is <em>recreated</em> on
     * respawn, so any long-lived task bound to the old one silently stops. Without being told, a caller
     * that remembers "this player already has a task" would never schedule a replacement.
     */
    public static ScheduledTask entityTimer(Plugin plugin, Entity entity, long delayTicks,
                                            long periodTicks, Consumer<ScheduledTask> task,
                                            Runnable retired) {
        return entity.getScheduler().runAtFixedRate(plugin, task, retired,
                Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    /** Global-region task, for world/server wide state that is not tied to a single location. */
    public static void global(Plugin plugin, Runnable task) {
        if (ranInPlace(plugin, task)) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    public static void globalLater(Plugin plugin, long delayTicks, Runnable task) {
        if (ranInPlace(plugin, task)) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(), Math.max(1L, delayTicks));
    }

    public static ScheduledTask globalTimer(Plugin plugin, long delayTicks, long periodTicks,
                                            Consumer<ScheduledTask> task) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task,
                Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    /** Off-thread work: disk I/O, HTTP, anything that must not touch the Bukkit API. */
    public static void async(Plugin plugin, Runnable task) {
        if (ranInPlace(plugin, task)) {
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
    }

    public static ScheduledTask asyncTimer(Plugin plugin, long delaySeconds, long periodSeconds,
                                           Consumer<ScheduledTask> task) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task,
                Math.max(1L, delaySeconds), Math.max(1L, periodSeconds), TimeUnit.SECONDS);
    }

    /** Off-thread, once, after a delay finer than a second — a poll interval, not a wait. */
    public static void asyncLater(Plugin plugin, long delay, TimeUnit unit, Runnable task) {
        if (ranInPlace(plugin, task)) {
            return;
        }
        Bukkit.getAsyncScheduler().runDelayed(plugin, ignored -> task.run(), Math.max(1L, delay), unit);
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
