package de.raindancer.core.world.companion;

import de.raindancer.core.world.combat.Combat;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Server;
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
    /** Ticks stuck on the way to its owner before it is brought to them. */
    private static final long STUCK_FOLLOWING = 100;
    /** Ticks stuck on the way to a spot before it picks another. */
    private static final long STUCK_EXPLORING = 60;
    /** Ticks it leaves a foe alone that it could not get at. */
    private static final long GIVE_UP_FOR = 400;
    /** A companion sitting by its owner gets up only once they are this much further than it follows. */
    private static final double SETTLED_SLACK = 1.5;

    private final CompanionService service;
    private final Combat combat;
    private final Grudges grudges;
    private final Server server;
    private final Targeting targeting = new Targeting();
    private final Map<UUID, Companion> missiles = new ConcurrentHashMap<>();
    private final java.util.Random random = new java.util.Random();
    private final Legs legs = new Legs(random);

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
        if (mob.getPassengers().contains(owner)) {
            carry(companion, mob, owner, spec);
            return;
        }
        Location here = mob.getLocation();
        Location there = owner.getLocation();
        if (here.distanceSquared(there) > CATCH_UP * CATCH_UP) {
            bringBack(companion, mob, there);
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
        if (tick % 20 == 0) {
            recover(companion, mob, owner, spec, tick, share);
        }
        boolean fleeing = share < Tactics.turnsBack(spec.fleesBelow(), spec.tactic());
        if (fleeing) {
            companion.target = null;
        } else if (tick % 10 == 0) {
            LivingEntity chosen = choose(companion, mob, owner, spec, tick);
            if (chosen == null || !chosen.getUniqueId().equals(companion.target)) {
                companion.chase.reset();
            }
            companion.target = chosen == null ? null : chosen.getUniqueId();
        }
        boolean idle = companion.wandering.ownerIdle(vec(there), tick);
        LivingEntity target = alive(companion.target, mob, owner, spec);
        if (target == null) {
            companion.target = null;
        }
        double vigour = Recovery.vigour(share);
        if (target != null) {
            fight(companion, mob, owner, target, spec, tick, vigour);
        } else if (!fleeing && (companion.staying() || idle) && spec.wanderRadius() > 0) {
            explore(companion, mob, owner, companion.staying() ? stayPoint(companion, mob) : there, spec, tick,
                    share, vigour);
        } else if (companion.staying()) {
            companion.exploring = null;
            legs.rest(companion, mob, spec, tick, owner.getEyeLocation(), vigour, true);
        } else {
            companion.exploring = null;
            follow(companion, mob, owner, spec, tick, fleeing, vigour, idle);
        }
    }

    // ------------------------------------------------------------------ following

    private void follow(Companion companion, Mob mob, Player owner, CompanionSpec spec, long tick, boolean fleeing,
                        double vigour, boolean idle) {
        Location there = owner.getLocation();
        Location aim = there.clone();
        if (spec.flies()) {
            aim.add(0, 1.5, 0);
        }
        double reach = fleeing ? 1.5 : FOLLOW_REACH;
        double flat = Math.hypot(aim.getX() - mob.getX(), aim.getZ() - mob.getZ());
        if (flat <= reach + (companion.legs.pose == Resting.Pose.STAND ? 0 : SETTLED_SLACK)
                && (!spec.flies() || Math.abs(aim.getY() - mob.getY()) < 2)) {
            legs.rest(companion, mob, spec, tick, owner.getEyeLocation(), vigour, idle);
            return;
        }
        double speed = fleeing ? spec.speed() * 1.2 : spec.speed() * vigour * (flat > 8 ? 1.3 : 1);
        long stuck = legs.go(companion, mob, aim, reach, speed, spec, tick, owner.getEyeLocation());
        if (stuck > STUCK_FOLLOWING) {
            bringBack(companion, mob, there);
        }
    }

    /** Beside its owner at once — when it fell too far behind or got stuck on the way. */
    private static void bringBack(Companion companion, Mob mob, Location there) {
        Location beside = there.clone().add(there.getDirection().setY(0).normalize().multiply(-1.5));
        if (!beside.getBlock().isPassable() || !beside.clone().add(0, 1, 0).getBlock().isPassable()) {
            beside = there.clone();
        }
        mob.teleportAsync(beside);
        companion.target = null;
        companion.exploring = null;
        companion.legs.course = null;
        companion.legs.progress.reset();
    }

    // ------------------------------------------------------------------ mending

    /** Once a second: a little health back, once it has been out of fights a while near its owner. */
    private void recover(Companion companion, Mob mob, Player owner, CompanionSpec spec, long tick, double share) {
        if (share >= 1) {
            return;
        }
        long calm = tick - Math.max(companion.lastAttack, companion.lastHurt);
        double amount = Recovery.mends(calm, mob.getLocation().distanceSquared(owner.getLocation()),
                spec.regenPerSecond());
        if (amount <= 0) {
            return;
        }
        double max = CompanionService.maxHealth(mob);
        mob.setHealth(Math.min(max, mob.getHealth() + amount));
        if (tick % 80 == 0) {
            mob.getWorld().spawnParticle(Particle.HEART, mob.getLocation().add(0, mob.getHeight() + 0.3, 0), 1,
                    0.15, 0.1, 0.15);
        }
    }

    // ------------------------------------------------------------------ fighting

    private void fight(Companion companion, Mob mob, Player owner, LivingEntity target, CompanionSpec spec,
                       long tick, double vigour) {
        Location here = mob.getLocation();
        Location there = target.getLocation();
        double distance = here.distance(there);
        boolean ready = tick - companion.lastAttack >= Math.max(companion.cooldown, 5);
        double reach = mob.getWidth() / 2 + target.getWidth() / 2 + 1.0;
        if (spec.fighting() == Fighting.RANGED) {
            boolean sees = mob.hasLineOfSight(target);
            if (ready && distance <= 16 && sees) {
                shoot(companion, mob, target, spec);
                attacked(companion, spec, tick);
            }
            if (sees) {
                companion.chase.reset();
            } else if (companion.chase.stuck(distance + 1000, tick, Tactics.patience(spec.tactic()))) {
                giveUp(companion, target, tick);
            }
        } else if (ready && distance <= reach && Math.abs(there.getY() - here.getY()) < 2) {
            target.damage(spec.damage(), DamageSource.builder(DamageType.MOB_ATTACK)
                    .withCausingEntity(mob).withDirectEntity(mob).build());
            mob.swingMainHand();
            attacked(companion, spec, tick);
            if (random.nextDouble() < 0.3) {
                companion.side = -companion.side;
            }
        }
        if (random.nextInt(120) == 0) {
            companion.side = -companion.side;
        }
        Tactics.Plan plan = Tactics.plan(new Tactics.Situation(vec(here), vec(there), vec(owner.getLocation()), reach,
                ready, companion.side, spec.fighting(), spec.tactic()));
        Location aim = new Location(mob.getWorld(), plan.aim().x(), plan.aim().y(), plan.aim().z());
        if (spec.flies()) {
            aim.setY(spec.fighting() == Fighting.RANGED ? Math.max(there.getY() + 2.5, here.getY() - 0.5)
                    : there.getY() + target.getHeight() / 2);
        }
        long stuck = legs.go(companion, mob, aim, plan.reach(), spec.speed() * plan.pace() * Math.max(0.8, vigour),
                spec, tick, target.getEyeLocation());
        if (stuck > 0 && random.nextInt(40) == 0) {
            companion.side = -companion.side;
        }
        boolean melee = spec.fighting() != Fighting.RANGED;
        if (melee && Math.hypot(there.getX() - here.getX(), there.getZ() - here.getZ()) > reach + 2.5
                && (plan.aim().equals(vec(there)) || stuck > 0)) {
            if (companion.chase.stuck(distance, tick, Tactics.patience(spec.tactic()))) {
                giveUp(companion, target, tick);
            }
        } else if (melee) {
            companion.chase.reset();
        }
    }

    private void attacked(Companion companion, CompanionSpec spec, long tick) {
        companion.lastAttack = tick;
        companion.cooldown = Tactics.cooldown(spec.attackTicks(), random);
    }

    /** It cannot get at it: leave it alone for a while. */
    private static void giveUp(Companion companion, LivingEntity target, long tick) {
        companion.gaveUp.put(target.getUniqueId(), tick + GIVE_UP_FOR);
        companion.target = null;
        companion.chase.reset();
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

    // ------------------------------------------------------------------ choosing a foe

    private LivingEntity choose(Companion companion, Mob mob, Player owner, CompanionSpec spec, long tick) {
        long now = System.currentTimeMillis();
        boolean pvp = combat.isPvpAllowed(mob.getWorld().getName());
        companion.gaveUp.values().removeIf(until -> until <= tick);
        java.util.Map<UUID, LivingEntity> found = new java.util.LinkedHashMap<>();
        for (UUID remembered : new UUID[]{grudges.hurtBy(owner.getUniqueId(), now).orElse(null),
                grudges.struck(owner.getUniqueId(), now).orElse(null), companion.target}) {
            LivingEntity foe = alive(remembered, mob, owner, spec);
            if (foe != null) {
                found.put(foe.getUniqueId(), foe);
            }
        }
        if (spec.stance() == Stance.AGGRESSIVE) {
            for (Entity near : owner.getNearbyEntities(LOOK_AROUND, LOOK_AROUND / 2, LOOK_AROUND)) {
                if (near instanceof LivingEntity living && (near instanceof Enemy
                        || (near instanceof Mob hunter && owner.equals(hunter.getTarget())))) {
                    found.putIfAbsent(near.getUniqueId(), living);
                }
            }
        }
        java.util.List<Targeting.Seen> wanted = new java.util.ArrayList<>();
        Location here = mob.getLocation();
        for (LivingEntity foe : found.values()) {
            if (companion.gaveUp.containsKey(foe.getUniqueId()) || !wanted(owner, spec, foe, now, pvp)) {
                continue;
            }
            wanted.add(new Targeting.Seen(foe.getUniqueId(),
                    grudges.hurtOwner(owner.getUniqueId(), foe.getUniqueId(), now),
                    foe instanceof Mob hunter && owner.equals(hunter.getTarget()),
                    grudges.ownerStruck(owner.getUniqueId(), foe.getUniqueId(), now),
                    foe.getHealth() / Math.max(1, CompanionService.maxHealth(foe)),
                    foe.getLocation().distance(here), covered(companion, foe)));
        }
        return Targeting.pick(wanted, companion.target).map(seen -> found.get(seen.id())).orElse(null);
    }

    /**
     * Whether the owner's other companions on this foe will finish it without this one. On its own foe, only those
     * that ranked before it count — so of three on a weak foe the first stay and the last moves on, not all of them.
     */
    private boolean covered(Companion companion, LivingEntity foe) {
        boolean own = foe.getUniqueId().equals(companion.target);
        java.util.List<Double> others = new java.util.ArrayList<>();
        for (Companion other : service.of(companion.owner())) {
            if (other != companion && foe.getUniqueId().equals(other.target)
                    && (!own || other.id().compareTo(companion.id()) < 0)) {
                others.add(other.spec().damage());
            }
        }
        return Targeting.covered(foe.getHealth(), others);
    }

    private boolean wanted(Player owner, CompanionSpec spec, LivingEntity other, long now, boolean pvp) {
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

    /** Alive, here, and no further from the owner than its tactic lets it chase. */
    private LivingEntity alive(UUID id, Mob mob, Player owner, CompanionSpec spec) {
        if (id == null) {
            return null;
        }
        double leash = Tactics.leash(spec.tactic());
        if (!(server.getEntity(id) instanceof LivingEntity living) || !living.isValid() || living.isDead()
                || living.getWorld() != mob.getWorld()
                || living.getLocation().distanceSquared(owner.getLocation()) > leash * leash) {
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

    /**
     * Off to a spot near its anchor now and then; there, it looks about — and settles down — until it sets off again.
     * Hurt, it keeps near enough its owner to mend, and sets off less often.
     */
    private void explore(Companion companion, Mob mob, Player owner, Location anchor, CompanionSpec spec, long tick,
                         double share, double vigour) {
        double radius = companion.staying() ? spec.wanderRadius()
                : Recovery.roam(spec.wanderRadius(), share, spec.regenPerSecond());
        Location spot = companion.exploring;
        if (spot != null && (spot.getWorld() != mob.getWorld()
                || spot.distanceSquared(anchor) > Math.pow(radius + 2, 2))) {
            spot = null;
        }
        boolean there = spot != null && Math.hypot(spot.getX() - mob.getX(), spot.getZ() - mob.getZ()) < 1.2
                && (!spec.flies() || Math.abs(spot.getY() - mob.getY()) < 1.5);
        if (spot == null || (there && tick % 20 == 0
                && Wandering.setsOff(spec.wanderEagerness() * vigour
                        * (companion.legs.pose == Resting.Pose.STAND ? 1 : 0.3), radius, random))) {
            Steering.Vec next = Wandering.spot(vec(anchor), radius, spec.flies(), random);
            spot = new Location(mob.getWorld(), next.x(), next.y(), next.z());
            companion.exploring = spot;
            there = false;
        }
        if (there) {
            legs.rest(companion, mob, spec, tick, owner.getEyeLocation(), vigour, true);
            return;
        }
        long stuck = legs.go(companion, mob, spot, 0.8, spec.speed() * 0.55 * vigour, spec, tick, spot);
        if (stuck > STUCK_EXPLORING) {
            companion.exploring = null;
        }
    }

    // ------------------------------------------------------------------ carrying

    private void carry(Companion companion, Mob mob, Player rider, CompanionSpec spec) {
        Input input = rider.getCurrentInput();
        double speed = spec.speed() * (input.isSprint() ? 1.5 : 1);
        Steering.Vec push = Steering.ridden(new Steering.Input(input.isForward(), input.isBackward(), input.isLeft(),
                        input.isRight(), input.isJump()), rider.getYaw(), rider.getPitch(), speed, spec.flies(),
                mob.getVelocity().getY(), mob.isOnGround());
        push = Legs.stepUp(mob, push, spec);
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

    private static Steering.Vec vec(Location at) {
        return Legs.vec(at);
    }
}
