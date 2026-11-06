package de.raindancer.core.world.companion;

/**
 * Whether a companion goes for something. Combat's own rules still have the last word when the bite lands; this keeps
 * a companion from running at what it could never hurt, or what nobody asked it to.
 */
public final class Targeting {

    public enum Kind {
        OWNER, OWN_PET, PLAYER, MONSTER, ANIMAL, UNFIGHTABLE
    }

    /**
     * @param hurtOwner it hurt the owner a moment ago
     * @param ownerHit  the owner hit it a moment ago
     * @param spared    only fought when the owner started it — a villager, another player's companion
     */
    public record Candidate(Kind kind, boolean hurtOwner, boolean ownerHit, boolean spared) {
    }

    public boolean attacks(Stance stance, Candidate candidate, boolean pvpHere) {
        if (stance == Stance.PASSIVE) {
            return false;
        }
        switch (candidate.kind()) {
            case OWNER, OWN_PET, UNFIGHTABLE -> {
                return false;
            }
            case PLAYER -> {
                if (!pvpHere) {
                    return false;
                }
            }
            default -> {
            }
        }
        if (candidate.hurtOwner()) {
            return true;
        }
        if (stance != Stance.AGGRESSIVE) {
            return false;
        }
        if (candidate.ownerHit()) {
            return true;
        }
        return candidate.kind() == Kind.MONSTER && !candidate.spared();
    }
}
