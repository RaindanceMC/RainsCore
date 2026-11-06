package de.raindancer.core.world.companion;

import de.raindancer.core.world.combat.Combat;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** What every companion does each tick, on its own thread: follow, fight, throw, mend, or carry its rider. */
final class CompanionBrain {

    /** Further than this from its owner, a companion is brought to them. */
    static final double CATCH_UP = 24;
    private static final double FOLLOW_REACH = 2.5;
    private static final double LOOK_AROUND = 10;
    private static final double SEEN_FROM = 16;
    private static final double RANGED_KEEP = 7;

    private final CompanionService service;
    private final Combat combat;
    private final Grudges grudges;
    private final Server server;
    private final Targeting targeting = new Targeting();
    private final Map<UUID, Companion> missiles = new ConcurrentHashMap<>();
    private final java.util.Random random = new java.util.Random();

    CompanionBrain(CompanionService service, Combat combat, Grudges grudges, Server server) {
        this.service = service;
        this.combat = combat;
        this.grudges = grudges;
        this.server = server;
    }

    Optional<Companion> missile(UUID projectile) {
        return Optional.ofNullable(missiles.remove(projectile));
    }

    boolean isMissile(UUID projectile) {
        return missiles.containsKey(projectile);
    }

    void tick(Companion companion, Mob mob) {
        long tick = ++companion.ticks;
        Player owner = server.getPlayer(companion.owner());
        if (owner == null || !owner.isValid() || owner.getWorld() != mob.getWorld()) {
            return;
        }
        CompanionSpec spec = companion.spec();
        if (mob instanceof EnderDragon dragon && dragon.getPhase() != EnderDragon.Phase.HOVER) {
            dragon.setPhase(EnderDragon.Phase.HOVER);
        }
        if (!spec.particle().isEmpty() && tick % 3 == 0) {
            de.raindancer.core.ui.effect.ParticleShows.at(mob.getLocation(), mob.getYaw(), spec.particle(), null, null,
                    4, spec.particleShape(), tick, 48, viewer -> true, null);
        }
        if (!companion.rigParts.isEmpty()) {
            RigDisplays.follow(server, mob, companion, spec, tick);
        }
        if (mob.getPassengers().contains(owner)) {
            carry(companion, mob, owner, spec);
            return;
        }
        Location here = mob.getLocation();
        Location there = owner.getLocation();
        if (here.distanceSquared(there) > CATCH_UP * CATCH_UP) {
            Location beside = there.clone().add(there.getDirection().setY(0).normalize().multiply(-1.5));
            mob.teleportAsync(beside);
            companion.target = null;
            return;
        }
        if (tick % 40 == 0) {
            for (Player viewer : mob.getWorld().getNearbyPlayers(here, SEEN_FROM)) {
                if (!viewer.equals(owner) && viewer.canSee(mob)) {
                    companion.hooks().seen(companion, viewer);
                }
            }
        }
        if (spec.fighting() == Fighting.HEALER && tick - companion.lastHeal >= spec.attackTicks() * 3L) {
            mend(companion, mob, owner, spec);
        }
        double share = mob.getHealth() / Math.max(1, CompanionService.maxHealth(mob));
        boolean fleeing = spec.fleesBelow() > 0 && share < spec.fleesBelow();
        if (fleeing) {
            companion.target = null;
        } else if (tick % 10 == 0) {
            LivingEntity chosen = choose(companion, mob, owner, spec);
            companion.target = chosen == null ? null : chosen.getUniqueId();
        }
        boolean idle = companion.wandering.ownerIdle(vec(there), tick);
        LivingEntity target = alive(companion.target, mob, owner);
        if (target == null) {
            companion.target = null;
        }
        Steering.Vec push;
        double fall = spec.flies() ? 0 : mob.getVelocity().getY();
        if (target != null) {
            push = fight(companion, mob, target, spec, tick, fall);
        } else if (!fleeing && (companion.staying() || idle) && spec.wanderRadius() > 0) {
            push = explore(companion, mob, companion.staying() ? stayPoint(companion, mob) : there, spec, tick, fall);
        } else if (companion.staying()) {
            push = new Steering.Vec(0, fall, 0);
        } else {
            companion.exploring = null;
            Location aim = there.clone();
            if (spec.flies()) {
                aim.add(0, 1.5, 0);
            }
            push = Steering.towards(vec(here), vec(aim), spec.speed(), fleeing ? 1.5 : FOLLOW_REACH, spec.flies(), fall);
        }
        Location lookAt = target != null ? target.getLocation()
                : companion.exploring != null ? companion.exploring : there;
        move(mob, push, lookAt, spec);
    }

    // ------------------------------------------------------------------ fighting

    private Steering.Vec fight(Companion companion, Mob mob, LivingEntity target, CompanionSpec spec, long tick,
                               double fall) {
        Location here = mob.getLocation();
        Location there = target.getLocation();
        double distance = here.distance(there);
        boolean ready = tick - companion.lastAttack >= spec.attackTicks();
        if (spec.fighting() == Fighting.RANGED) {
            if (ready && distance <= 16 && mob.hasLineOfSight(target)) {
                shoot(companion, mob, target, spec);
                companion.lastAttack = tick;
            }
            return distance > RANGED_KEEP + 2
                    ? Steering.towards(vec(here), vec(there), spec.speed(), RANGED_KEEP, spec.flies(), fall)
                    : new Steering.Vec(0, fall, 0);
        }
        double reach = mob.getWidth() / 2 + target.getWidth() / 2 + 1.0;
        if (ready && distance <= reach) {
            target.damage(spec.damage(), DamageSource.builder(DamageType.MOB_ATTACK)
                    .withCausingEntity(mob).withDirectEntity(mob).build());
            mob.swingMainHand();
            companion.lastAttack = tick;
        }
        Location aim = spec.flies() ? there.clone().add(0, target.getHeight() / 2, 0) : there;
        return Steering.towards(vec(here), vec(aim), spec.speed() * 1.15, Math.max(0.3, reach - 0.4), spec.flies(),
                fall);
    }

    private void shoot(Companion companion, Mob mob, LivingEntity target, CompanionSpec spec) {
        Class<? extends Entity> kind = spec.missile().getEntityClass();
        @SuppressWarnings("unchecked")
        Class<? extends Projectile> thrown = kind != null && Projectile.class.isAssignableFrom(kind)
                ? (Class<? extends Projectile>) kind : Snowball.class;
        Vector toward = target.getEyeLocation().toVector().subtract(mob.getEyeLocation().toVector()).normalize()
                .multiply(1.4);
        Projectile missile = mob.launchProjectile(thrown, toward);
        if (missile instanceof Fireball fireball) {
            fireball.setIsIncendiary(false);
            fireball.setYield(0);
            fireball.setDirection(toward);
        }
        if (missile instanceof ShulkerBullet bullet) {
            bullet.setTarget(target);
        }
        missiles.put(missile.getUniqueId(), companion);
    }

    /** A missile hit: its damage, as the companion's. */
    void hit(Companion companion, Projectile missile, Entity struck) {
        if (struck instanceof LivingEntity living && !struck.getUniqueId().equals(companion.owner())
                && service.byMob(struck.getUniqueId()).map(other -> !other.owner().equals(companion.owner()))
                .orElse(true)) {
            Entity mob = companion.mob() == null ? null : server.getEntity(companion.mob());
            DamageSource.Builder source = DamageSource.builder(DamageType.MOB_PROJECTILE).withDirectEntity(missile);
            if (mob != null) {
                source.withCausingEntity(mob);
            }
            living.damage(companion.spec().damage(), source.build());
        }
        missile.remove();
    }

    private void mend(Companion companion, Mob mob, Player owner, CompanionSpec spec) {
        companion.lastHeal = companion.ticks;
        double max = CompanionService.maxHealth(owner);
        if (owner.getHealth() < max && mob.getLocation().distanceSquared(owner.getLocation()) < 100) {
            owner.heal(spec.damage());
            owner.getWorld().spawnParticle(Particle.HEART, owner.getLocation().add(0, 2.1, 0), 3, 0.3, 0.2, 0.3);
        }
        mob.setHealth(Math.min(CompanionService.maxHealth(mob), mob.getHealth() + spec.damage() / 2));
    }

    private LivingEntity choose(Companion companion, Mob mob, Player owner, CompanionSpec spec) {
        long now = System.currentTimeMillis();
        boolean pvp = combat.isPvpAllowed(mob.getWorld().getName());
        LivingEntity current = alive(companion.target, mob, owner);
        if (current != null && wanted(companion, owner, spec, current, now, pvp)) {
            return current;
        }
        for (UUID remembered : new UUID[]{grudges.hurtBy(owner.getUniqueId(), now).orElse(null),
                grudges.struck(owner.getUniqueId(), now).orElse(null)}) {
            LivingEntity foe = alive(remembered, mob, owner);
            if (foe != null && wanted(companion, owner, spec, foe, now, pvp)) {
                return foe;
            }
        }
        if (spec.stance() != Stance.AGGRESSIVE) {
            return null;
        }
        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity near : owner.getNearbyEntities(LOOK_AROUND, LOOK_AROUND / 2, LOOK_AROUND)) {
            if (near instanceof LivingEntity living && near instanceof Enemy
                    && wanted(companion, owner, spec, living, now, pvp)) {
                double distance = near.getLocation().distanceSquared(owner.getLocation());
                if (distance < best) {
                    best = distance;
                    nearest = living;
                }
            }
        }
        return nearest;
    }

    private boolean wanted(Companion companion, Player owner, CompanionSpec spec, LivingEntity other, long now,
                           boolean pvp) {
        return targeting.attacks(spec.stance(), new Targeting.Candidate(kind(owner, other),
                grudges.hurtOwner(owner.getUniqueId(), other.getUniqueId(), now),
                grudges.ownerStruck(owner.getUniqueId(), other.getUniqueId(), now), spared(other)), pvp);
    }

    private Targeting.Kind kind(Player owner, LivingEntity other) {
        if (other.equals(owner)) {
            return Targeting.Kind.OWNER;
        }
        Optional<Companion> theirs = service.byMob(other.getUniqueId());
        if (theirs.isPresent() && theirs.get().owner().equals(owner.getUniqueId())) {
            return Targeting.Kind.OWN_PET;
        }
        if (other instanceof ArmorStand || other.isInvulnerable() || other.isDead()) {
            return Targeting.Kind.UNFIGHTABLE;
        }
        if (other instanceof Player player) {
            return player.getGameMode().isInvulnerable() ? Targeting.Kind.UNFIGHTABLE : Targeting.Kind.PLAYER;
        }
        return other instanceof Enemy ? Targeting.Kind.MONSTER : Targeting.Kind.ANIMAL;
    }

    private boolean spared(LivingEntity other) {
        return other instanceof Villager || other instanceof WanderingTrader || service.isCompanion(other.getUniqueId())
                || (other instanceof Tameable tame && tame.isTamed()) || other.customName() != null;
    }

    private LivingEntity alive(UUID id, Mob mob, Player owner) {
        if (id == null) {
            return null;
        }
        if (!(server.getEntity(id) instanceof LivingEntity living) || !living.isValid() || living.isDead()
                || living.getWorld() != mob.getWorld()
                || living.getLocation().distanceSquared(owner.getLocation()) > CATCH_UP * CATCH_UP) {
            return null;
        }
        return living;
    }

    // ------------------------------------------------------------------ exploring

    /** Where it was told to stay — its own spot when told. */
    private static Location stayPoint(Companion companion, Mob mob) {
        if (companion.stayAt == null) {
            companion.stayAt = mob.getLocation().clone();
        }
        return companion.stayAt;
    }

    /** Off to a spot near its anchor now and then; there, it looks about until it sets off again. */
    private Steering.Vec explore(Companion companion, Mob mob, Location anchor, CompanionSpec spec, long tick,
                                 double fall) {
        Location spot = companion.exploring;
        if (spot != null && (spot.getWorld() != mob.getWorld()
                || spot.distanceSquared(anchor) > Math.pow(spec.wanderRadius() + 2, 2))) {
            spot = null;
        }
        if (spot == null || (tick % 20 == 0 && mob.getLocation().distanceSquared(spot) < 2.5
                && Wandering.setsOff(spec.wanderEagerness(), spec.wanderRadius(), random))) {
            Steering.Vec next = Wandering.spot(vec(anchor), spec.wanderRadius(), spec.flies(), random);
            spot = new Location(mob.getWorld(), next.x(), next.y(), next.z());
            companion.exploring = spot;
        }
        return Steering.towards(vec(mob.getLocation()), vec(spot), spec.speed() * 0.55, 0.8, spec.flies(), fall);
    }

    // ------------------------------------------------------------------ moving

    private void carry(Companion companion, Mob mob, Player rider, CompanionSpec spec) {
        Input input = rider.getCurrentInput();
        double speed = spec.speed() * (input.isSprint() ? 1.5 : 1);
        Steering.Vec push = Steering.ridden(new Steering.Input(input.isForward(), input.isBackward(), input.isLeft(),
                        input.isRight(), input.isJump()), rider.getYaw(), rider.getPitch(), speed, spec.flies(),
                mob.getVelocity().getY(), mob.isOnGround());
        push = stepUp(mob, push, spec);
        if (mob instanceof EnderDragon) {
            Location next = mob.getLocation().add(push.x(), push.y(), push.z());
            next.setYaw(rider.getYaw() + 180);
            next.setPitch(0);
            mob.teleportAsync(next, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN,
                    TeleportFlag.EntityState.RETAIN_PASSENGERS);
        } else {
            mob.setVelocity(new Vector(push.x(), push.y(), push.z()));
            mob.setRotation(rider.getYaw(), 0);
            mob.setBodyYaw(rider.getYaw());
        }
        companion.unreportedRide += Math.hypot(push.x(), push.z()) + (spec.flies() ? Math.abs(push.y()) : 0);
        if (companion.unreportedRide >= 10) {
            double blocks = companion.unreportedRide;
            companion.unreportedRide = 0;
            companion.hooks().ridden(companion, blocks);
        }
    }

    private void move(Mob mob, Steering.Vec push, Location lookAt, CompanionSpec spec) {
        push = stepUp(mob, push, spec);
        boolean moving = Math.hypot(push.x(), push.z()) > 0.01;
        float yaw = moving ? Steering.yawOf(push) : yawTowards(mob.getLocation(), lookAt);
        if (mob instanceof EnderDragon) {
            Location next = mob.getLocation().add(push.x(), push.y(), push.z());
            next.setYaw(yaw + 180);
            next.setPitch(0);
            mob.teleportAsync(next, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN,
                    TeleportFlag.EntityState.RETAIN_PASSENGERS);
            return;
        }
        mob.setVelocity(new Vector(push.x(), push.y(), push.z()));
        mob.setRotation(yaw, 0);
        mob.setBodyYaw(yaw);
    }

    /** A walker blocked by one block ahead hops it. */
    private static Steering.Vec stepUp(Mob mob, Steering.Vec push, CompanionSpec spec) {
        if (spec.flies() || !mob.isOnGround() || Math.hypot(push.x(), push.z()) < 0.05) {
            return push;
        }
        Vector ahead = new Vector(push.x(), 0, push.z()).normalize().multiply(mob.getWidth() / 2 + 0.4);
        Block front = mob.getLocation().add(ahead).getBlock();
        if (front.isSolid() && front.getRelative(0, 1, 0).isPassable()) {
            return new Steering.Vec(push.x(), Steering.JUMP, push.z());
        }
        return push;
    }

    private static float yawTowards(Location from, Location to) {
        return Steering.yawOf(new Steering.Vec(to.getX() - from.getX(), 0, to.getZ() - from.getZ()));
    }

    private static Steering.Vec vec(Location at) {
        return new Steering.Vec(at.getX(), at.getY(), at.getZ());
    }
}
