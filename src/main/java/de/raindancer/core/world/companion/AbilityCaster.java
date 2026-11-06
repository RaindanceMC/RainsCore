package de.raindancer.core.world.companion;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.combat.Attack;
import de.raindancer.core.world.combat.Combat;
import de.raindancer.core.world.combat.Verdict;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkull;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Optional;

/** Carries out a ridden companion's ability, on its rider's thread; whatever it reaches is changed on its own. */
final class AbilityCaster {

    private static final double BEAM = 20;

    private final CompanionService service;
    private final CompanionBrain brain;
    private final Combat combat;
    private final Plugin core;
    private final Server server;

    AbilityCaster(CompanionService service, CompanionBrain brain, Combat combat, Plugin core, Server server) {
        this.service = service;
        this.brain = brain;
        this.combat = combat;
        this.core = core;
        this.server = server;
    }

    /** @return whether it went off — false while it is not ready again */
    boolean cast(Companion companion, Mob mob, Player rider) {
        CompanionSpec spec = companion.spec();
        CompanionAbility ability = spec.ability();
        if (ability == null) {
            return false;
        }
        long now = server.getCurrentTick();
        if (now < companion.abilityReadyAt) {
            rider.sendActionBar(Component.text(ability.title() + " is ready in "
                    + Abilities.secondsLeft(now, companion.abilityReadyAt) + "s", NamedTextColor.GRAY));
            return false;
        }
        companion.abilityReadyAt = now + Abilities.cooldownTicks(ability, spec.abilityHaste());
        double strength = spec.damage() * spec.abilityPower();
        Vector looking = rider.getEyeLocation().getDirection().normalize();
        switch (ability) {
            case WITHER_SKULLS -> {
                for (int i = 0; i < 3; i++) {
                    Vector spread = looking.clone().add(new Vector((i - 1) * 0.06, 0, (1 - i) * 0.06)).normalize();
                    Scheduling.entityLater(core, mob, 1 + i * 4L, () -> skull(companion, mob, spread, strength));
                }
                sound(spec, mob, Sound.ENTITY_WITHER_SHOOT);
            }
            case SONIC_BOOM -> sonicBoom(companion, mob, rider, looking, strength);
            case FIREBALL -> {
                Location from = mob.getEyeLocation().add(looking.clone().multiply(2));
                LargeFireball ball = mob.getWorld().spawn(from, LargeFireball.class, made -> {
                    made.setShooter(mob);
                    made.setDirection(looking.clone().multiply(1.5));
                    made.setYield(0);
                    made.setIsIncendiary(false);
                });
                brain.shot(ball, companion, strength * 2, null, 3.5);
                sound(spec, mob, Sound.ENTITY_GHAST_SHOOT);
            }
            case FLAME_VOLLEY -> {
                for (int i = 0; i < 5; i++) {
                    double spread = (i - 2) * 0.09;
                    Vector aim = looking.clone().add(new Vector(-looking.getZ(), 0, looking.getX()).multiply(spread))
                            .normalize();
                    Scheduling.entityLater(core, mob, 1 + i * 2L, () -> flame(companion, mob, aim, strength));
                }
                sound(spec, mob, Sound.ENTITY_BLAZE_SHOOT);
            }
            case GUST -> gust(companion, mob, rider, looking, strength);
            case ROAR -> roar(companion, mob, rider, strength);
            case CURSE -> curse(companion, mob, rider, strength);
            case MENDING_RAIN -> mendingRain(spec, mob, rider, strength);
            case ILLUSION -> illusion(companion, mob, rider);
        }
        rider.sendActionBar(Component.text(ability.title() + "!", NamedTextColor.GOLD));
        try {
            companion.hooks().abilityUsed(companion, ability);
        } catch (RuntimeException broken) {
            core.getLogger().warning("A companion's plugin failed on its ability: " + broken);
        }
        return true;
    }

    // ------------------------------------------------------------------ the abilities

    private void skull(Companion companion, Mob mob, Vector toward, double strength) {
        if (!mob.isValid()) {
            return;
        }
        Location from = mob.getEyeLocation().add(toward.clone().multiply(1.8));
        WitherSkull skull = mob.getWorld().spawn(from, WitherSkull.class, made -> {
            made.setShooter(mob);
            made.setDirection(toward.clone().multiply(1.3));
            made.setYield(0);
            made.setIsIncendiary(false);
        });
        brain.shot(skull, companion, strength * 1.2, new PotionEffect(PotionEffectType.WITHER, 80, 1), 0);
    }

    private void flame(Companion companion, Mob mob, Vector toward, double strength) {
        if (!mob.isValid()) {
            return;
        }
        Location from = mob.getEyeLocation().add(toward.clone().multiply(1.6));
        org.bukkit.entity.SmallFireball ball = mob.getWorld().spawn(from, org.bukkit.entity.SmallFireball.class,
                made -> {
                    made.setShooter(mob);
                    made.setDirection(toward.clone().multiply(1.4));
                    made.setIsIncendiary(false);
                    made.setYield(0);
                });
        brain.shot(ball, companion, strength * 0.7, null, 0);
    }

    private void sonicBoom(Companion companion, Mob mob, Player rider, Vector looking, double strength) {
        Location start = mob.getEyeLocation();
        for (double step = 1; step <= BEAM; step += 1) {
            mob.getWorld().spawnParticle(Particle.SONIC_BOOM, start.clone().add(looking.clone().multiply(step)), 1);
        }
        sound(companion.spec(), mob, Sound.ENTITY_WARDEN_SONIC_BOOM);
        Vector origin = start.toVector();
        for (Entity near : mob.getNearbyEntities(BEAM, BEAM, BEAM)) {
            if (Abilities.distanceToBeam(origin, looking, BEAM, near.getLocation().add(0, near.getHeight() / 2, 0)
                    .toVector()) <= 1.5 && fair(rider, near)) {
                hurt(mob, (LivingEntity) near, strength * 2.5, DamageType.SONIC_BOOM, looking.clone().multiply(1.2)
                        .setY(0.3), null);
            }
        }
    }

    private void gust(Companion companion, Mob mob, Player rider, Vector looking, double strength) {
        Location at = mob.getLocation();
        for (double step = 1; step <= 8; step += 1.5) {
            mob.getWorld().spawnParticle(Particle.GUST, at.clone().add(0, 1, 0).add(looking.clone().multiply(step)), 1);
        }
        sound(companion.spec(), mob, Sound.ENTITY_BREEZE_WIND_BURST);
        for (Entity near : mob.getNearbyEntities(8, 6, 8)) {
            if (Abilities.inCone(at.toVector(), looking, 8, 50, near.getLocation().toVector()) && fair(rider, near)) {
                hurt(mob, (LivingEntity) near, strength * 0.8, DamageType.MOB_ATTACK,
                        looking.clone().setY(0).normalize().multiply(1.8).setY(0.9), null);
            }
        }
    }

    private void roar(Companion companion, Mob mob, Player rider, double strength) {
        Location at = mob.getLocation();
        mob.getWorld().spawnParticle(Particle.SWEEP_ATTACK, at.clone().add(0, 1, 0), 24, 3, 0.5, 3);
        mob.getWorld().spawnParticle(Particle.CLOUD, at.clone().add(0, 0.5, 0), 40, 3, 0.3, 3, 0.05);
        sound(companion.spec(), mob, Sound.ENTITY_RAVAGER_ROAR);
        for (Entity near : mob.getNearbyEntities(6, 3, 6)) {
            if (fair(rider, near)) {
                Vector away = near.getLocation().toVector().subtract(at.toVector()).setY(0);
                away = away.lengthSquared() < 1e-6 ? new Vector(0, 0, 1) : away.normalize();
                hurt(mob, (LivingEntity) near, strength * 1.5, DamageType.MOB_ATTACK, away.multiply(1.4).setY(0.45),
                        new PotionEffect(PotionEffectType.SLOWNESS, 30, 6));
            }
        }
    }

    private void curse(Companion companion, Mob mob, Player rider, double strength) {
        sound(companion.spec(), mob, Sound.ENTITY_ELDER_GUARDIAN_CURSE);
        for (Entity near : mob.getNearbyEntities(12, 8, 12)) {
            if (fair(rider, near)) {
                LivingEntity living = (LivingEntity) near;
                hurt(mob, living, strength * 0.8, DamageType.MAGIC, null,
                        new PotionEffect(PotionEffectType.SLOWNESS, 120, 1));
                Scheduling.entity(core, living, () -> {
                    living.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 120, 0));
                    living.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 200, 1));
                    if (living instanceof Player cursed) {
                        cursed.spawnParticle(Particle.ELDER_GUARDIAN, cursed.getLocation(), 1);
                    }
                });
            }
        }
    }

    private void mendingRain(CompanionSpec spec, Mob mob, Player rider, double strength) {
        Location at = mob.getLocation();
        mob.getWorld().spawnParticle(Particle.FALLING_WATER, at.clone().add(0, 3, 0), 60, 4, 0.5, 4);
        mob.getWorld().spawnParticle(Particle.HEART, at.clone().add(0, 2, 0), 10, 3, 0.5, 3);
        sound(spec, mob, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
        for (Entity near : mob.getNearbyEntities(8, 5, 8)) {
            if (near instanceof Player friend && !fair(rider, friend)) {
                mend(friend, strength * 3);
            }
        }
        mend(rider, strength * 3);
        double max = CompanionService.maxHealth(mob);
        mob.setHealth(Math.min(max, mob.getHealth() + strength * 2));
    }

    private void mend(Player player, double amount) {
        Scheduling.entity(core, player, () -> {
            player.heal(amount);
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
        });
    }

    private void illusion(Companion companion, Mob mob, Player rider) {
        mob.getWorld().spawnParticle(Particle.CLOUD, mob.getLocation().add(0, 1, 0), 40, 1, 1, 1, 0.05);
        sound(companion.spec(), mob, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE);
        PotionEffect hidden = new PotionEffect(PotionEffectType.INVISIBILITY, 100, 0, false, false);
        rider.addPotionEffect(hidden);
        mob.addPotionEffect(hidden);
        for (Entity near : mob.getNearbyEntities(10, 6, 10)) {
            if (fair(rider, near)) {
                LivingEntity living = (LivingEntity) near;
                Scheduling.entity(core, living, () -> {
                    living.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0));
                    if (living instanceof Mob hunter && rider.equals(hunter.getTarget())) {
                        hunter.setTarget(null);
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------ fairness and harm

    /** Whether an ability of this rider's companion may touch it — see {@link Abilities#hits}. */
    boolean fair(Player rider, Entity entity) {
        if (!(entity instanceof LivingEntity living) || entity instanceof ArmorStand || !living.isValid()
                || living.isDead()) {
            return false;
        }
        Optional<Companion> companion = service.byMob(entity.getUniqueId());
        boolean own = companion.map(found -> found.owner().equals(rider.getUniqueId())).orElse(false);
        boolean player = entity instanceof Player;
        boolean other = companion.isPresent() && !own;
        boolean pvp = false;
        if ((player || other) && !entity.getUniqueId().equals(rider.getUniqueId())) {
            Location where = entity.getLocation();
            Location from = rider.getLocation();
            Attack attack = new Attack(Attack.Fighter.PLAYER, rider.getUniqueId(), Attack.Fighter.PLAYER,
                    player ? entity.getUniqueId() : companion.get().owner(), entity.getWorld().getName(), true,
                    new Attack.At(where.getX(), where.getY(), where.getZ()),
                    new Attack.At(from.getX(), from.getY(), from.getZ()), Attack.Means.OTHER);
            pvp = combat.decide(attack).verdict() == Verdict.ALLOWED;
        }
        return Abilities.hits(new Abilities.Victim(entity.getUniqueId().equals(rider.getUniqueId()), own, player,
                other, entity instanceof Enemy, entity instanceof Mob hunter && rider.equals(hunter.getTarget()), pvp));
    }

    /** Its damage, with the companion as the one behind it — Combat rules on it once more on the way in. */
    void hurt(Mob mob, LivingEntity victim, double amount, DamageType type, Vector push, PotionEffect effect) {
        Scheduling.entity(core, victim, () -> {
            if (!victim.isValid()) {
                return;
            }
            double before = victim.getHealth();
            victim.setNoDamageTicks(0);
            victim.damage(amount, DamageSource.builder(type).withCausingEntity(mob).withDirectEntity(mob).build());
            boolean landed = victim.getHealth() < before || victim.isDead();
            if (!landed) {
                return;
            }
            if (push != null) {
                victim.setVelocity(victim.getVelocity().add(push));
            }
            if (effect != null) {
                victim.addPotionEffect(effect);
            }
        });
    }

    private static void sound(CompanionSpec spec, Mob mob, Sound sound) {
        if (!spec.silent()) {
            mob.getWorld().playSound(mob.getLocation(), sound, 1.2f, 1f);
        }
    }
}
