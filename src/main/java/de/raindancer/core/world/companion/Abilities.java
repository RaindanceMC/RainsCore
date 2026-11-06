package de.raindancer.core.world.companion;

import org.bukkit.util.Vector;

/** The arithmetic of abilities, without a server: how long to wait, whom they may touch, what a beam or cone covers. */
final class Abilities {

    private Abilities() {
    }

    /**
     * Whatever an ability could land on, as far as fairness goes.
     *
     * @param owner          the rider themselves
     * @param ownCompanion   one of the rider's own companions
     * @param player         a player
     * @param otherCompanion somebody else's companion — hurting it is hurting them
     * @param enemy          a monster
     * @param huntsOwner     a mob that is after the rider
     * @param pvpAllowed     Combat lets the rider fight that player (or that companion's owner) here
     */
    record Victim(boolean owner, boolean ownCompanion, boolean player, boolean otherCompanion, boolean enemy,
                  boolean huntsOwner, boolean pvpAllowed) {
    }

    /** Monsters and whatever hunts the rider; people only where they may be fought; never a bystander. */
    static boolean hits(Victim victim) {
        if (victim.owner() || victim.ownCompanion()) {
            return false;
        }
        if (victim.player() || victim.otherCompanion()) {
            return victim.pvpAllowed();
        }
        return victim.enemy() || victim.huntsOwner();
    }

    static int cooldownTicks(CompanionAbility ability, double haste) {
        return Math.max(20, (int) Math.round(ability.cooldownSeconds() * 20 / Math.max(0.01, haste)));
    }

    static int secondsLeft(long now, long readyAt) {
        return readyAt <= now ? 0 : (int) Math.ceil((readyAt - now) / 20.0);
    }

    /** How far {@code point} lies from the beam {@code start + t·along}, t from 0 to {@code length}. */
    static double distanceToBeam(Vector start, Vector along, double length, Vector point) {
        Vector direction = along.clone().normalize();
        double t = Math.max(0, Math.min(length, point.clone().subtract(start).dot(direction)));
        return start.clone().add(direction.multiply(t)).distance(point);
    }

    /** Whether {@code point} is in front of {@code at}, within {@code reach} and {@code halfAngle} degrees of facing. */
    static boolean inCone(Vector at, Vector facing, double reach, double halfAngle, Vector point) {
        Vector to = point.clone().subtract(at);
        double distance = to.length();
        if (distance > reach) {
            return false;
        }
        if (distance < 1e-6) {
            return true;
        }
        return Math.toDegrees(to.angle(facing)) <= halfAngle;
    }
}
