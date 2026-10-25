package de.raindancer.core.ui.hologram;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * A screen put up somewhere, as written to its file: {@code "3 lobby 10.25 66.20 -4.50 balance"}, and the owner's id
 * after it for a screen a player put up in their base.
 *
 * @param owner the player who put it up; null for one staff put up
 */
public record PlacedScreen(int number, String world, double x, double y, double z, String kind, UUID owner) {

    public PlacedScreen(int number, String world, double x, double y, double z, String kind) {
        this(number, world, x, y, z, kind, null);
    }

    public String line() {
        return String.format(Locale.ROOT, "%d %s %.2f %.2f %.2f %s", number, world, x, y, z, kind)
                + (owner == null ? "" : " " + owner);
    }

    public static Optional<PlacedScreen> parse(String line) {
        if (line == null) {
            return Optional.empty();
        }
        String[] parts = line.trim().split(" ");
        if ((parts.length != 6 && parts.length != 7) || parts[1].isEmpty() || parts[5].isEmpty()) {
            return Optional.empty();
        }
        try {
            UUID owner = parts.length == 7 ? UUID.fromString(parts[6]) : null;
            return Optional.of(new PlacedScreen(Integer.parseInt(parts[0]), parts[1], Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]), Double.parseDouble(parts[4]), parts[5], owner));
        } catch (IllegalArgumentException garbled) {
            return Optional.empty();
        }
    }

    public PlacedScreen raised(double by) {
        return new PlacedScreen(number, world, x, y + by, z, kind, owner);
    }

    public PlacedScreen at(String otherWorld, double ox, double oy, double oz) {
        return new PlacedScreen(number, otherWorld, ox, oy, oz, kind, owner);
    }

    public double distanceSquared(String otherWorld, double ox, double oy, double oz) {
        if (!world.equals(otherWorld)) {
            return Double.MAX_VALUE;
        }
        return (x - ox) * (x - ox) + (y - oy) * (y - oy) + (z - oz) * (z - oz);
    }
}
