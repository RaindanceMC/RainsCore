package de.raindancer.core.world.movement;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.effect.Effects;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.function.Predicate;

/**
 * Jump pads: a pressure plate on a slime block throws whoever steps on it forward and up, the way they are looking,
 * and the landing does not hurt. A map builder makes one without any command; which worlds have them is the
 * caller's ({@code where}).
 *
 * <p>Register it as a listener. Several plugins may each register their own for their own worlds.
 */
public final class JumpPads implements Listener {

    /** Upper bounds on a launch, whatever a setting says — beyond these a pad is a way out of the map. */
    public static final double MOST_UP = 3.0;
    public static final double MOST_FORWARD = 4.0;

    /** How long after a launch the landing is spared. */
    public static final long SOFT_LANDING_MILLIS = 8_000;

    /** The cue played at a pad. */
    public static final String CUE = "core:jump-pad";

    private final Plugin plugin;
    private final Effects effects;
    private final Predicate<Player> where;
    private final double forward;
    private final double up;
    private final SoftLandings landings = new SoftLandings(System::currentTimeMillis);

    /**
     * @param where   whether pads work for this player where they stand (their world, their game)
     * @param forward blocks per tick of speed along the look, before {@link #MOST_FORWARD}
     * @param up      upward speed, before {@link #MOST_UP}
     */
    public JumpPads(Plugin plugin, Effects effects, Predicate<Player> where, double forward, double up) {
        this.plugin = plugin;
        this.effects = effects;
        this.where = where;
        this.forward = forward;
        this.up = up;
        if (effects != null && !effects.isDefined(CUE)) {
            effects.define(CUE, de.raindancer.core.ui.effect.Effect.of(
                    new de.raindancer.core.ui.effect.SoundCue("entity.slime.jump", 1.0f, 0.8f)));
        }
    }

    /** The speed a pad gives somebody looking towards {@code yaw}: flat along the look, plus straight up. */
    public static Vector launch(float yaw, double forward, double up) {
        double radians = Math.toRadians(yaw);
        double along = Math.min(Math.max(0, forward), MOST_FORWARD);
        return new Vector(-Math.sin(radians) * along, Math.min(Math.max(0, up), MOST_UP), Math.cos(radians) * along);
    }

    /** Whether a block is a pad: a pressure plate with slime under it. */
    public static boolean isPad(Block plate) {
        return plate != null && Tag.PRESSURE_PLATES.isTagged(plate.getType())
                && plate.getRelative(BlockFace.DOWN).getType() == Material.SLIME_BLOCK;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStep(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL || !isPad(event.getClickedBlock())) {
            return;
        }
        Player player = event.getPlayer();
        if (!where.test(player)) {
            return;
        }
        Vector speed = launch(player.getLocation().getYaw(), forward, up);
        landings.launched(player.getUniqueId());
        Scheduling.entity(plugin, player, () -> player.setVelocity(speed));
        if (effects != null) {
            effects.play(player.getUniqueId(), CUE);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLanding(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player
                && landings.spared(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        landings.forget(event.getPlayer().getUniqueId());
    }
}
