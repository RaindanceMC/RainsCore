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
 * @param particle   a particle effect it wears (Core's {@code ParticleShows} names), empty for none
 */
public record CompanionSpec(EntityType type, double height, boolean flies, boolean baby, Fighting fighting,
                            Stance stance, double health, double damage, double speed, double fleesBelow,
                            EntityType missile, boolean rideable, int attackTicks, Component name,
                            String particle, de.raindancer.core.ui.effect.ParticleShape particleShape) {

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
        particleShape = particleShape == null || !fitsSmallBody(particleShape)
                ? de.raindancer.core.ui.effect.ParticleShape.AURA : particleShape;
    }

    /** Wings are drawn the size of a player and would hang in the air over a small pet. */
    public static boolean fitsSmallBody(de.raindancer.core.ui.effect.ParticleShape shape) {
        return shape != null && !shape.isWings();
    }

    public CompanionSpec particle(String v, de.raindancer.core.ui.effect.ParticleShape shape) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, v, shape);
    }

    public static CompanionSpec of(EntityType type) {
        return new CompanionSpec(type, 0.4, false, false, Fighting.MELEE, Stance.DEFENSIVE, 20, 3, 0.3, 0,
                EntityType.SNOWBALL, true, 20, Component.empty(), "", null);
    }

    /** The scale that makes a mob {@code mobHeight} blocks tall stand {@link #height} players tall. */
    public double scaleFor(double mobHeight) {
        if (mobHeight <= 0) {
            return LARGEST_SCALE;
        }
        return Math.min(LARGEST_SCALE, height * PLAYER_HEIGHT / mobHeight);
    }

    public CompanionSpec height(double v) {
        return new CompanionSpec(type, v, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape);
    }

    public CompanionSpec flies(boolean v) {
        return new CompanionSpec(type, height, v, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape);
    }

    public CompanionSpec baby(boolean v) {
        return new CompanionSpec(type, height, flies, v, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape);
    }

    public CompanionSpec fighting(Fighting v) {
        return new CompanionSpec(type, height, flies, baby, v, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape);
    }

    public CompanionSpec stance(Stance v) {
        return new CompanionSpec(type, height, flies, baby, fighting, v, health, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape);
    }

    public CompanionSpec health(double v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, v, damage, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape);
    }

    public CompanionSpec damage(double v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, v, speed, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape);
    }

    public CompanionSpec speed(double v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, v, fleesBelow, missile, rideable, attackTicks, name, particle, particleShape);
    }

    public CompanionSpec fleesBelow(double v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, v, missile, rideable, attackTicks, name, particle, particleShape);
    }

    public CompanionSpec missile(EntityType v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, v, rideable, attackTicks, name, particle, particleShape);
    }

    public CompanionSpec rideable(boolean v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, v, attackTicks, name, particle, particleShape);
    }

    public CompanionSpec attackTicks(int v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, v, name, particle, particleShape);
    }

    public CompanionSpec name(Component v) {
        return new CompanionSpec(type, height, flies, baby, fighting, stance, health, damage, speed, fleesBelow, missile, rideable, attackTicks, v, particle, particleShape);
    }
}
