package de.raindancer.core.moderation.feed;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Set;

/** Feeds the {@link AdminSessionRecorder} from the server; the recorder ignores anybody not in a stay. */
public final class AdminSessionWatcher implements Listener {

    /** Teleports staff mean; pearls, beds and dismounting are not worth a line. */
    private static final Set<String> TELEPORTS = Set.of("COMMAND", "PLUGIN", "SPECTATE", "UNKNOWN",
            "NETHER_PORTAL", "END_PORTAL", "END_GATEWAY");

    private final AdminSessionRecorder recorder;

    public AdminSessionWatcher(AdminSessionRecorder recorder) {
        this.recorder = recorder;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        recorder.command(event.getPlayer().getUniqueId(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        recorder.placed(event.getPlayer().getUniqueId(), event.getBlockPlaced().getType().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        recorder.broken(event.getPlayer().getUniqueId(), event.getBlock().getType().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo().getWorld() == null || !TELEPORTS.contains(event.getCause().name())) {
            return;
        }
        recorder.teleported(event.getPlayer().getUniqueId(),
                event.getFrom().getWorld() == null ? null : event.getFrom().getWorld().getName(),
                event.getTo().getWorld().getName());
    }

    /** Only switches made by command: admin mode putting a side's game mode back is not something staff did. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (event.getCause() == PlayerGameModeChangeEvent.Cause.COMMAND) {
            recorder.gameMode(event.getPlayer().getUniqueId(), event.getPlayer().getGameMode().name(),
                    event.getNewGameMode().name());
        }
    }
}
