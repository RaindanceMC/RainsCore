package de.raindancer.core.world.protection;

import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.Location;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WaterMob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetEvent;

import java.util.Optional;

/**
 * Mob behaviour inside claims: spawning, entry and targeting.
 * <p>
 * Entry prevention works on {@link EntityMoveEvent} (Paper) — the mob is stopped at the border rather
 * than being teleported or killed, which keeps pathfinding sane and avoids surprise mob farms.
 */
public final class MobControlListener implements Listener {


    private final Land land;

    /** A companion — somebody's pet monster — counts as its owner, never as a monster at a claim's border. */
    private final java.util.function.Predicate<java.util.UUID> companion;

    public MobControlListener(Land land) {
        this(land, id -> false);
    }

    public MobControlListener(Land land, java.util.function.Predicate<java.util.UUID> companion) {
        this.companion = companion == null ? id -> false : companion;
        this.land = land;
    }

    private boolean flagDenied(Location location, LandFlag flag) {
        if (!land.landFlags().isEnforced(flag)) {
            return false;
        }
        Optional<ProtectedArea> area = land.areaAt(location);
        if (area.isEmpty()) {
            return false;
        }
        return !land.flags().isAllowed(area.get(), flag);
    }

    /**
     * The same question as {@link #denied}, but about a creature rather than a block.
     * <p>
     * A player's claim is resolved the way the border tracker resolves it, tolerance included. Asking
     * for their exact block instead means somebody standing on their own roof — inside the claim for
     * every other purpose — loses the protection the flag promises them.
     */
    private boolean deniedFor(Entity victim, LandFlag flag) {
        if (!land.landFlags().isEnforced(flag)) {
            return false;
        }
        if (victim instanceof Player player) {
            ProtectedArea tracked = land.areaAround(player).orElse(null);
            return !land.landFlags().isAllowedForTracked(tracked, player.getLocation(), flag, player);
        }
        return !land.landFlags().isAllowedFor(victim, flag);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        LivingEntity entity = event.getEntity();
        Location location = entity.getLocation();

        // A trial spawner is a spawner block precisely as far as this flag is concerned — its own
        // reason exists because vanilla tracks trial-chamber state separately, not because a claim
        // owner would ever want the two treated differently. Without this a vault room's mobs walked
        // straight past SPAWNER_SPAWNING and MONSTER_SPAWNING both, governed by nothing at all.
        if (reason == CreatureSpawnEvent.SpawnReason.SPAWNER
                || reason == CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER) {
            if (flagDenied(location, LandFlag.SPAWNER_SPAWNING)) {
                event.setCancelled(true);
            }
            return;
        }
        // A potion of infestation or oozing, not the world spawning something on its own — governed
        // separately, see LandFlag.POTION_SPAWNING.
        if (reason == CreatureSpawnEvent.SpawnReason.POTION_EFFECT) {
            if (flagDenied(location, LandFlag.POTION_SPAWNING)) {
                event.setCancelled(true);
            }
            return;
        }
        // Only natural-ish spawns are governed; eggs, breeding and commands stay in the owner's control.
        if (!isNaturalReason(reason)) {
            return;
        }
        if (isHostile(entity)) {
            if (flagDenied(location, LandFlag.MONSTER_SPAWNING)) {
                event.setCancelled(true);
            }
        } else if (entity instanceof Animals || entity instanceof WaterMob) {
            if (flagDenied(location, LandFlag.ANIMAL_SPAWNING)) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Whether two spots belong to the same claim, so a step between them is not an entry.
     *
     * <p>Both being unclaimed does <em>not</em> count as the same ground: a monster walking across open
     * land has not entered anything, and answering true there would make every step outside a claim look
     * internal to it.
     */
    private boolean sameGround(Location from, Location to) {
        String fromArea = land.areaAt(from).map(ProtectedArea::id).orElse(null);
        String toArea = land.areaAt(to).map(ProtectedArea::id).orElse(null);
        return fromArea != null && fromArea.equals(toArea);
    }

    private boolean isNaturalReason(CreatureSpawnEvent.SpawnReason reason) {
        return switch (reason) {
            case NATURAL, REINFORCEMENTS, VILLAGE_INVASION, VILLAGE_DEFENSE, PATROL, RAID, SILVERFISH_BLOCK,
                 SLIME_SPLIT, TRAP, LIGHTNING, JOCKEY, MOUNT, DROWNED, SPELL -> true;
            default -> false;
        };
    }

    /** One answer for the whole package — see {@link InteractionClassifier#isHostile}. */
    private boolean isHostile(Entity entity) {
        return InteractionClassifier.isHostile(entity) && !isCompanion(entity);
    }

    private boolean isCompanion(Entity entity) {
        return entity != null && entity.getUniqueId() != null && companion.test(entity.getUniqueId());
    }

    /** Stops hostile mobs at the border when MONSTER_ENTRY is denied. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityMove(EntityMoveEvent event) {
        if (!land.landFlags().isEnforced(LandFlag.MONSTER_ENTRY)) {
            return;
        }
        if (!event.hasChangedBlock()) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (!isHostile(entity)) {
            return;
        }
        // Only a move that actually *enters* protected ground is a candidate. Asking the flag about the
        // destination alone was wrong in the one case that matters: the built-in default is "no entry", so a
        // mob walking from inside a claim out into open country was refused too — the wilderness answered
        // "not allowed" and it stood at the border for ever. The promise in the comment below was not being
        // kept at all.
        Optional<ProtectedArea> to = land.areaAt(event.getTo());
        if (to.isEmpty()) {
            return;   // leaving, or walking about outside. Neither is entering anything.
        }
        if (land.flags().isAllowed(to.get(), LandFlag.MONSTER_ENTRY)) {
            return;
        }
        // A mob already inside — spawned there, or there before the flag flipped — may move about and may
        // walk back out. Only a step across the border from somewhere else is stopped.
        if (sameGround(event.getFrom(), event.getTo())) {
            return;
        }
        event.setCancelled(true);
    }

    /**
     * Stops hostile mobs from taking aim at players.
     * <p>
     * Listens to {@link EntityTargetEvent} rather than the LivingEntity subtype so every acquisition path
     * is covered, and enforces the flag unconditionally — including retaliation. An earlier version made
     * an exception for {@code TARGET_ATTACKED_ENTITY} so players could still be fought back against, but
     * that meant the flag silently stopped working the moment somebody hit a mob, which is not what
     * "monsters may not target players" says.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (!land.landFlags().isEnforced(LandFlag.MONSTER_TARGETING)) {
            return;
        }
        if (!(event.getTarget() instanceof Player player) || !isHostile(event.getEntity())) {
            return;
        }
        if (deniedFor(player, LandFlag.MONSTER_TARGETING)) {
            event.setTarget(null);
            event.setCancelled(true);
        }
    }

    /**
     * Drops a target a mob was already holding when it crosses into the claim.
     * <p>
     * Cancelling the target event only blocks new acquisitions; a mob that locked on outside would
     * otherwise keep chasing its victim across the border.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityMoveClearTarget(EntityMoveEvent event) {
        if (!land.landFlags().isEnforced(LandFlag.MONSTER_TARGETING) || !event.hasChangedBlock()) {
            return;
        }
        if (!(event.getEntity() instanceof Mob mob) || !isHostile(mob)) {
            return;
        }
        if (!(mob.getTarget() instanceof Player hunted)) {
            return;
        }
        // The victim's claim decides, exactly as when the target was first acquired, so a mob that walks
        // into a claim protecting its quarry drops them.
        if (deniedFor(hunted, LandFlag.MONSTER_TARGETING)) {
            mob.setTarget(null);
        }
    }

    /** Mobs hurting players or other mobs, governed separately from PvP. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMobDamage(EntityDamageByEntityEvent event) {
        if (!land.landFlags().isEnforced(LandFlag.MOB_DAMAGE)) {
            return;
        }
        Entity damager = event.getDamager();
        // A pet's bite is its owner's: Combat rules on it, not this flag.
        if (isCompanion(damager) || damager instanceof Projectile shot && shot.getShooter() instanceof Entity from
                && isCompanion(from)) {
            return;
        }
        // Only mob-caused damage; player damage is covered by PvP and the permission checks.
        if (damager instanceof Player) {
            return;
        }
        if (damager instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof Player) {
                return;
            }
            if (!(projectile.getShooter() instanceof LivingEntity)) {
                return;
            }
        } else if (!(damager instanceof LivingEntity)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (deniedFor(victim, LandFlag.MOB_DAMAGE)) {
            event.setCancelled(true);
        }
    }
}
