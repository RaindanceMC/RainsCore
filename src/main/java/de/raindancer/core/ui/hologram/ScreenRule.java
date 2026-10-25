package de.raindancer.core.ui.hologram;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Which screen is meant, how far a nudge goes, and who gets or loses a display of a personal screen. */
public final class ScreenRule {

    public static final double DEFAULT_NUDGE = 0.25;
    public static final double LEAST_NUDGE = 0.05;
    public static final double MOST_NUDGE = 10;

    /** Who gets a display now, and whose goes. */
    public record Viewers(Set<java.util.UUID> add, Set<java.util.UUID> remove) {
    }

    public int nextNumber(List<PlacedScreen> placed) {
        return placed.stream().mapToInt(PlacedScreen::number).max().orElse(0) + 1;
    }

    public Optional<PlacedScreen> nearest(List<PlacedScreen> placed, String world, double x, double y, double z,
                                          double within) {
        return placed.stream()
                .filter(screen -> screen.distanceSquared(world, x, y, z) <= within * within)
                .min(Comparator.comparingDouble(screen -> screen.distanceSquared(world, x, y, z)));
    }

    /** Blocks to move by, from what was typed (a comma works too); null for something that is no number. */
    public Double nudge(String typed) {
        if (typed == null || typed.isBlank()) {
            return DEFAULT_NUDGE;
        }
        try {
            double blocks = Math.abs(Double.parseDouble(typed.strip().replace(',', '.')));
            return Math.max(LEAST_NUDGE, Math.min(MOST_NUDGE, blocks));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    public Viewers viewers(Set<java.util.UUID> showing, Set<java.util.UUID> near) {
        Set<java.util.UUID> add = new HashSet<>(near);
        add.removeAll(showing);
        Set<java.util.UUID> remove = new HashSet<>(showing);
        remove.removeAll(near);
        return new Viewers(Set.copyOf(add), Set.copyOf(remove));
    }
}
