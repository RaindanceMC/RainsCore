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

    /**
     * One it could go for, as it weighs them up.
     *
     * @param hurtOwner      it hurt the owner a moment ago
     * @param threatensOwner it is going for the owner
     * @param ownerHit       the owner hit it a moment ago
     * @param healthShare    its health as a share of its most
     * @param distance       from the companion
     * @param covered        the owner's other companions on it will finish it without this one
     */
    public record Seen(java.util.UUID id, boolean hurtOwner, boolean threatensOwner, boolean ownerHit,
                       double healthShare, double distance, boolean covered) {

        int urgency() {
            return hurtOwner ? 3 : threatensOwner ? 2 : ownerHit ? 1 : 0;
        }

        /** Health counts in quarters, so a scratch does not outweigh a few blocks. */
        int weakness() {
            return (int) Math.ceil(Math.max(0, Math.min(1, healthShare)) * 4);
        }
    }

    private static final java.util.Comparator<Seen> FIRST = java.util.Comparator.comparing(Seen::covered)
            .thenComparing(java.util.Comparator.comparingInt(Seen::urgency).reversed())
            .thenComparingInt(Seen::weakness).thenComparingDouble(Seen::distance);

    /**
     * Which of those it fights: what hurt its owner, then what goes for them, then what they hit, then the weakest,
     * then the nearest — and only once nothing else is left, one the owner's other companions will finish anyway.
     * It keeps its current foe unless something more urgent turns up.
     */
    public static java.util.Optional<Seen> pick(java.util.List<Seen> wanted, java.util.UUID current) {
        java.util.Optional<Seen> best = wanted.stream().min(FIRST);
        if (best.isEmpty() || current == null) {
            return best;
        }
        return wanted.stream().filter(seen -> seen.id().equals(current))
                .filter(seen -> (!seen.covered() || best.get().covered()) && seen.urgency() >= best.get().urgency())
                .findFirst().or(() -> best);
    }

    /** Whether the bites the others on a foe have ready are enough for what health it has left. */
    public static boolean covered(double health, java.util.List<Double> othersDamage) {
        return !othersDamage.isEmpty() && othersDamage.stream().mapToDouble(Double::doubleValue).sum() >= health;
    }
}
