package de.raindancer.core.ui.camera;

import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Plays {@link CameraPath}s to single players: a cinematic flight — an intro, a showcase — with captions.
 *
 * <p>The player is put into spectator mode and watches through an invisible display only they can see, which the
 * server sends on one {@link CutsceneTimeline segment} ahead with that much interpolation, so the client glides it
 * smoothly. Spectating moves the player with it, so the world around the camera loads. Sneaking (the game's own
 * "stop spectating") skips. The game mode they had is kept on the player until it is given back, so a crash or a
 * quit mid-flight finds them restored on their next join.
 *
 * <p>Real spectator mode, so not for players whose farms or pickup must keep running — {@link PlayerCamera} is for
 * those. One instance per plugin; {@link #close} on disable.
 */
public final class Cutscenes implements Listener {

    public enum End { FINISHED, SKIPPED, LEFT, CANCELLED }

    private static final NamespacedKey MODE = new NamespacedKey("rainscore", "cutscene_mode");
    private static final int SEGMENT_TICKS = 10;
    private static final double HOLD_SECONDS = 1.5;

    private final Plugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    private static final class Session {
        final CutsceneTimeline timeline;
        ItemDisplay camera;
        final BiConsumer<Player, CameraPath.Key> caption;
        final Consumer<End> done;
        ScheduledTask task;
        int tick;

        Session(CutsceneTimeline timeline, BiConsumer<Player, CameraPath.Key> caption, Consumer<End> done) {
            this.timeline = timeline;
            this.caption = caption;
            this.done = done;
        }
    }

    public Cutscenes(Plugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public boolean playing(UUID player) {
        return sessions.containsKey(player);
    }

    /**
     * @param world   where the path's coordinates are
     * @param caption called when a key with a caption is reached, on the player's thread
     * @param done    called once when it is over, on the player's thread (not for {@link End#LEFT})
     * @return false when they are already watching one
     */
    public boolean play(Player player, World world, CameraPath path, BiConsumer<Player, CameraPath.Key> caption,
                        Consumer<End> done) {
        if (sessions.containsKey(player.getUniqueId())) {
            return false;
        }
        CutsceneTimeline timeline = new CutsceneTimeline(path, SEGMENT_TICKS, HOLD_SECONDS);
        Location start = location(world, path.at(0));
        player.getPersistentDataContainer().set(MODE, PersistentDataType.STRING, player.getGameMode().name());
        player.setGameMode(GameMode.SPECTATOR);
        Session session = new Session(timeline, caption, done);
        sessions.put(player.getUniqueId(), session);
        // The camera only once they are there: spawned far from them, their client would never be sent it.
        player.teleportAsync(start).thenRun(() -> Scheduling.entity(plugin, player, () -> {
            if (sessions.get(player.getUniqueId()) != session) {
                return;
            }
            session.camera = world.spawn(start, ItemDisplay.class, display -> {
                display.setPersistent(false);
                display.setVisibleByDefault(false);
                display.setTeleportDuration(SEGMENT_TICKS);
            });
            player.showEntity(plugin, session.camera);
            session.task = Scheduling.entityTimer(plugin, player, 1, 1, task -> tick(player, session), () -> {
            });
        }));
        return true;
    }

    /** Ends theirs at once, if any; {@code done} hears {@link End#CANCELLED}. */
    public void cancel(Player player) {
        finish(player, End.CANCELLED);
    }

    public void close() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            finish(player, End.CANCELLED);
        }
        HandlerList.unregisterAll(this);
    }

    private void tick(Player player, Session session) {
        if (!player.isOnline() || sessions.get(player.getUniqueId()) != session) {
            return;
        }
        int tick = session.tick++;
        if (tick == 2) {
            // A tick after it was shown: the client has the display, so it can be watched through.
            player.setSpectatorTarget(session.camera);
        }
        session.timeline.captionAt(tick).ifPresent(index ->
                session.caption.accept(player, session.timeline.path().keys().get(index)));
        if (session.timeline.over(tick)) {
            finish(player, End.FINISHED);
            return;
        }
        if (session.timeline.sendAt(tick)) {
            session.camera.teleportAsync(location(session.camera.getWorld(),
                    session.timeline.path().at(session.timeline.targetSeconds(tick))));
        } else if (tick > 2 && player.getSpectatorTarget() != session.camera) {
            player.setSpectatorTarget(session.camera);
        }
    }

    private void finish(Player player, End end) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.task != null) {
            session.task.cancel();
        }
        ItemDisplay camera = session.camera;
        if (camera != null && player.getSpectatorTarget() == camera) {
            player.setSpectatorTarget(null);
        }
        restore(player);
        if (camera != null) {
            Scheduling.entity(plugin, camera, camera::remove);
        }
        if (end != End.LEFT) {
            session.done.accept(end);
        }
    }

    /** Their game mode from before, if one is kept on them. */
    private static void restore(Player player) {
        String mode = player.getPersistentDataContainer().get(MODE, PersistentDataType.STRING);
        if (mode == null) {
            return;
        }
        player.getPersistentDataContainer().remove(MODE);
        try {
            player.setGameMode(GameMode.valueOf(mode));
        } catch (IllegalArgumentException unknown) {
            player.setGameMode(player.getServer().getDefaultGameMode());
        }
    }

    private static Location location(World world, CameraPath.Frame frame) {
        return new Location(world, frame.x(), frame.y(), frame.z(), CameraPath.wrap(frame.yaw()), frame.pitch());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onStopSpectating(PlayerStopSpectatingEntityEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.camera != null && event.getSpectatorTarget() == session.camera) {
            event.setCancelled(true);
            finish(event.getPlayer(), End.SKIPPED);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        // A crash or a stop mid-flight: give back what they had.
        restore(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        finish(event.getPlayer(), End.LEFT);
    }
}
