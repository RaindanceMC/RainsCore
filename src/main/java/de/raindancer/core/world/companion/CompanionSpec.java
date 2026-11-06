package de.raindancer.core.world.companion;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.EntityType;

/**
 * What a companion is made as, and how it behaves — handed to {@link CompanionService#summon} and changed with
 * {@link Companion#update}. Every {@code with} is a new spec.
 *
 * @param height     how tall it stands, in player heights; it is never drawn larger than its wild self
 * @param health     its most health, in half hearts
 * @param damage     a bite, or a healer's mend, in half hearts
 * @param speed      blocks per tick, following and ridden
 * @param fleesBelow below this share of its health it stops fighting and keeps to its owner; 0 never
 * @param missile    what a {@link Fighting#RANGED} one throws; nothing it throws breaks or burns anything
 * @param attackTicks ticks between two bites or throws
 * @param wanderRadius    how far it explores while its owner is idle or it stays; 0 never
 * @param wanderEagerness how often, 0 to 1
 * @param silent     makes no sound at all — its owner muted it
 * @param particle   a particle effect it wears (Core's {@code ParticleShows} names), empty for none
 * @param tactic     how it goes about a fight, its owner's choice
 * @param regenPerSecond half hearts it mends a second once it has been out of fights a while near its owner
 * @param grumpiness how often it squares up to other people's companions, 0 to 1 — for show, nobody is hurt
 * @param ability    what it does when its rider swings, or null for nothing
 * @param abilityPower how hard that hits or heals, 1 as it comes
 * @param abilityHaste how much sooner it is ready again, 1 as it comes
 */
public record CompanionSpec(EntityType type, double height, boolean flies, boolean baby, Fighting fighting,
                            Stance stance, double health, double damage, double speed, double fleesBelow,
                            EntityType missile, boolean rideable, int attackTicks, Component name,
                            String particle, de.raindancer.core.ui.effect.ParticleShape particleShape,
                            double wanderRadius, double wanderEagerness, boolean silent, Tactic tactic,
                            double regenPerSecond, double grumpiness, CompanionAbility ability,
                            double abilityPower, double abilityHaste) {

    public static final double LARGEST_SCALE = 0.95;
    public static final double FASTEST = 1.5;
    private static final double PLAYER_HEIGHT = 1.8;

    public CompanionSpec {
        height = Math.max(0.05, height);
        health = Math.max(1, health);
        damage = Math.max(0, damage);
        speed = Math.max(0.02, Math.min(FASTEST, speed));
        fleesBelow = Math.max(0, Math.min(1, fleesBelow));
        attackTicks = Math.max(5, attackTicks);
        fighting = fighting == null ? Fighting.MELEE : fighting;
        stance = stance == null ? Stance.DEFENSIVE : stance;
        missile = missile == null ? EntityType.SNOWBALL : missile;
        name = name == null ? Component.empty() : name;
        particle = particle == null ? "" : particle;
        wanderRadius = Math.max(0, Math.min(24, wanderRadius));
        wanderEagerness = Math.max(0, Math.min(1, wanderEagerness));
        tactic = tactic == null ? Tactic.BALANCED : tactic;
        regenPerSecond = Math.max(0, regenPerSecond);
        grumpiness = Math.max(0, Math.min(1, grumpiness));
        abilityPower = Math.max(0.1, Math.min(10, abilityPower));
        abilityHaste = Math.max(0.25, Math.min(4, abilityHaste));
        particleShape = particleShape == null || !fitsSmallBody(particleShape)
                ? de.raindancer.core.ui.effect.ParticleShape.AURA : particleShape;
    }

    /** The shape before abilities, kept so plugins built against it still link. */
    public CompanionSpec(EntityType type, double height, boolean flies, boolean baby, Fighting fighting,
                         Stance stance, double health, double damage, double speed, double fleesBelow,
                         EntityType missile, boolean rideable, int attackTicks, Component name,
                         String particle, de.raindancer.core.ui.effect.ParticleShape particleShape,
                         double wanderRadius, double wanderEagerness, boolean silent, Tactic tactic,
                         double regenPerSecond, double grumpiness) {
        this(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable,
                attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic,
                regenPerSecond, grumpiness, null, 1, 1);
    }

    /** Wings are drawn the size of a player and would hang in the air over a small pet. */
    public static boolean fitsSmallBody(de.raindancer.core.ui.effect.ParticleShape shape) {
        return shape != null && !shape.isWings();
    }

    public CompanionSpec silent(boolean v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, v, tactic, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec wander(double radius, double eagerness) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape, radius, eagerness, silent, tactic, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec particle(String v, de.raindancer.core.ui.effect.ParticleShape shape) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, v, shape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public static CompanionSpec of(EntityType type) {
        return new CompanionSpec(type, 0.4, false, false, Fighting.MELEE, Stance.DEFENSIVE, 20, 3, 0.3, 0,
                EntityType.SNOWBALL, true, 20, Component.empty(), "", null, 6, 0.5, false, Tactic.BALANCED, 0.5, 0, null, 1, 1);
    }

    /** The scale that makes a mob {@code mobHeight} blocks tall stand {@link #height} players tall. */
    public double scaleFor(double mobHeight) {
        if (mobHeight <= 0) {
            return LARGEST_SCALE;
        }
        return Math.min(LARGEST_SCALE, height * PLAYER_HEIGHT / mobHeight);
    }

    public CompanionSpec height(double v) {
        return new CompanionSpec(type, v, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec flies(boolean v) {
        return new CompanionSpec(type, height, v, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec baby(boolean v) {
        return new CompanionSpec(type, height, flies, v, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec fighting(Fighting v) {
        return new CompanionSpec(type, height, flies, baby, v, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec stance(Stance v) {
        return new CompanionSpec(type, height, flies, baby, fighting, v, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec health(double v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, v, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec damage(double v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, v, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec speed(double v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, v, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec fleesBelow(double v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, v, missile, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec missile(EntityType v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, v, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec rideable(boolean v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, v, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec attackTicks(int v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, v, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec name(Component v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, v, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec tactic(Tactic v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, v, regenPerSecond, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec regenPerSecond(double v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, v, grumpiness, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec grumpiness(double v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, v, ability, abilityPower, abilityHaste);
    }

    public CompanionSpec ability(CompanionAbility v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, v, abilityPower, abilityHaste);
    }

    public CompanionSpec abilityPower(double v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, ability, v, abilityHaste);
    }

    public CompanionSpec abilityHaste(double v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape, wanderRadius, wanderEagerness, silent, tactic, regenPerSecond, grumpiness, ability, abilityPower, v);
    }
}
