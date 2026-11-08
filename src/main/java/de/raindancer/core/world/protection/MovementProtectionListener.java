package de.raindancer.core.world.protection;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.movement.Moves;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turning somebody back at the border.
 *
 * <p>The {@link LandFlag#WALK_IN} flag, which is what makes the way-in flags a complete set: without it an owner
 * can close teleporting, pearling, elytra and riptide and still have people wander through the front gate.
 *
 * <h2>Why this is Core's and not the region plugin's</h2>
 * Because the flag is. A claims module, an arena and a plot world all want "this tier may not come in", and
 * three implementations of it would be three slightly different ideas of what crossing a border means. What the
 * region plugin still owns is <em>where</em> the border is — this asks {@link Land} and does not know.
 *
 * <p>Only the block position is looked at, and only when it changes. A player standing still generates a move
 * event several times a second, and answering it properly each time is a claim lookup per tick per player.
 */
public final class MovementProtectionListener implements Listener {

    private final Land land;
    private final Messages messages;

    /** Throttles the refusal per player, so walking into a wall does not fill their screen. */
    private final Map<UUID, Long> lastRefusal = new ConcurrentHashMap<>();

    private static final long QUIET_MILLIS = 1_500L;

    /**
     * How long somebody dropped at a border is spared the fall.
     *
     * <p>Eight seconds: long enough to reach the ground from any height a glider arrives at, short enough to be
     * a grace rather than an exemption. Spent on the first fall, so the usual case ends it sooner.
     */
    private static final long GLIDE_GRACE = 8_000L;

    /** Players the plugin took out of the air, and when. */
    private final Map<UUID, Long> caughtFalling = new ConcurrentHashMap<>();

    public MovementProtectionListener(Land land, Messages messages) {
        this.land = land;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!Moves.changedBlock(event)) {
            return;
        }
        if (refusedAt(event.getPlayer(), event.getFrom(), event.getTo())) {
            event.setCancelled(true);
            return;
        }
        groundIfGlidingIn(event.getPlayer(), event.getFrom(), event.getTo());
    }

    /**
     * Takes somebody out of the air when they glide into ground that does not allow it.
     *
     * <p>{@link LandFlag#ELYTRA_FLIGHT} was enforced on {@code EntityToggleGlideEvent} alone, which fires when
     * gliding <em>starts</em>. Start outside, glide in, and nothing ever asked — so the flag stopped people
     * taking off inside a claim and did nothing about people arriving under their own power. The old plugin had
     * the same hole, so this is a flag that only ever half worked rather than a regression.
     *
     * <p>The glide is stopped rather than the movement refused: cancelling a move at elytra speed rubber-bands
     * the player back and forth across the border, which is worse than what it prevents.
     *
     * <p>Arriving only. Gliding out, or about inside, is left alone for the same reason every other way-in flag
     * leaves it alone — being dropped on the way out is the flag punishing somebody for obeying it.
     */
    private void groundIfGlidingIn(Player player, Location from, Location to) {
        if (!player.isGliding() || !land.landFlags().isEnforced(LandFlag.ELYTRA_FLIGHT)
                || land.isBypassing(player)) {
            return;
        }
        Optional<ProtectedArea> entering = land.areaAt(to);
        if (entering.isEmpty()) {
            return;
        }
        Optional<ProtectedArea> leaving = land.areaAt(from);
        if (leaving.isPresent() && leaving.get().id().equals(entering.get().id())) {
            return;
        }
        if (land.landFlags().isAllowedForTracked(entering.get(), to, LandFlag.ELYTRA_FLIGHT, player)) {
            return;
        }
        player.setGliding(false);
        // The fall is the plugin's doing, so the plugin eats it. Without this an owner could pair this flag
        // with fall damage and have a border that kills whoever flies into it.
        caughtFalling.put(player.getUniqueId(), System.currentTimeMillis());
        refuse(player, entering.get());
    }

    /**
     * Whether this player is falling because they were grounded at a border a moment ago.
     *
     * <p>Asked by the damage listener. Clears itself either way, so the grace covers one fall and not the next.
     */
    public boolean wasCaughtFalling(UUID who) {
        Long at = caughtFalling.remove(who);
        return at != null && System.currentTimeMillis() - at <= GLIDE_GRACE;
    }

    /**
     * The same for a teleport, because arriving is a way in too.
     *
     * <p>{@link LandFlag#TELEPORT_IN} governs whether teleporting is a way in at all; this governs whether that
     * person may be there once they have. A tier that may not walk in may not be dropped in either — otherwise
     * the flag is a suggestion.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (refusedAt(event.getPlayer(), event.getFrom(), event.getTo())) {
            event.setCancelled(true);
        }
    }

    /**
     * Whether this step crosses into somewhere they may not be.
     *
     * <p>Moving <em>within</em> an area is never refused, whatever the flag says: somebody already inside when
     * it was switched off has to be able to leave, and refusing every step would pin them where they stand.
     * Leaving is likewise never refused — the flag is about coming in.
     */
    private boolean refusedAt(Player player, Location from, Location to) {
        if (!land.landFlags().isEnforced(LandFlag.WALK_IN) || land.isBypassing(player)) {
            return false;
        }
        Optional<ProtectedArea> entering = land.areaAt(to);
        if (entering.isEmpty()) {
            return false;   // walking out, or about outside. Neither is coming in.
        }
        Optional<ProtectedArea> leaving = land.areaAt(from);
        if (leaving.isPresent() && leaving.get().id().equals(entering.get().id())) {
            return false;   // moving about inside, including on the way out
        }
        if (land.flags().isAllowedFor(entering.get(), LandFlag.WALK_IN, player)
                || land.allowedByOthers(player, entering.get(), LandAction.ENTER)) {
            return false;
        }
        refuse(player, entering.get());
        return true;
    }

    private void refuse(Player who, ProtectedArea area) {
        long now = System.currentTimeMillis();
        Long last = lastRefusal.get(who.getUniqueId());
        if (last != null && now - last < QUIET_MILLIS) {
            return;
        }
        lastRefusal.put(who.getUniqueId(), now);
        who.sendActionBar(messages.prefixed("land.walk-in-refused", "claim", area.name()));
    }

    /** Called when a player leaves, or the throttle grows an entry per player who has ever been on. */
    public void forget(UUID player) {
        lastRefusal.remove(player);
        caughtFalling.remove(player);
    }
}
