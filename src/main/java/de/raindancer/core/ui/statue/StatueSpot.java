package de.raindancer.core.ui.statue;

import java.util.Locale;
import java.util.Optional;

/**
 * Where one statue stands, as written in Core's {@code statues.yml}: {@code "casino 10.50 64.00 -3.25 90.0 richest 2 -"}
 * (world, ground, facing, board, place, group). {@code y} is the ground its pedestal stands on. Statues put up
 * together as a podium share a {@code group}; one placed on its own has {@code "-"}. The same form Economy wrote
 * before 0.78.0, so the pieces of a statue it put up are found by the same key.
 */
public record StatueSpot(String world, double x, double y, double z, float yaw, String board, int place,
                         String group) {

    public static final String ALONE = "-";
    /** Places of a ranking: a top three. */
    public static final int PODIUM = 3;
    /** Most statues of one thank-you board a server puts up. */
    public static final int MOST = 64;

    public static Optional<StatueSpot> parse(String line) {
        if (line == null) {
            return Optional.empty();
        }
        String[] words = line.trim().split("\\s+");
        if (words.length != 8) {
            return Optional.empty();
        }
        String board = words[5].toLowerCase(Locale.ROOT);
        Optional<Integer> place = readPlace(true, words[6]);
        if (!StatueBoard.validId(board) || place.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StatueSpot(words[0], Double.parseDouble(words[1]), Double.parseDouble(words[2]),
                    Double.parseDouble(words[3]), Float.parseFloat(words[4]), board, place.get(), words[7]));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    /** A place as typed, a leading {@code #} allowed: 1 to 3 for a ranking, 1 to {@link #MOST} for a thank-you. */
    public static Optional<Integer> readPlace(boolean thankYou, String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String digits = typed.strip().replaceFirst("^#", "");
        if (!digits.matches("\\d{1,3}")) {
            return Optional.empty();
        }
        int place = Integer.parseInt(digits);
        return place >= 1 && place <= (thankYou ? MOST : PODIUM) ? Optional.of(place) : Optional.empty();
    }

    public String written() {
        return String.format(Locale.ROOT, "%s %.2f %.2f %.2f %.1f %s %d %s", world, x, y, z, yaw, board, place,
                group);
    }

    /** What everything standing on this spot is tagged with, before a {@code #}. */
    public String key() {
        return written();
    }

    public boolean standalone() {
        return ALONE.equals(group);
    }

    /** The same statue somewhere else; moved away from its podium it stands on its own. */
    public StatueSpot at(String otherWorld, double ox, double oy, double oz, float facing) {
        return new StatueSpot(otherWorld, ox, oy, oz, facing, board, place, ALONE);
    }

    public double distanceSquared(String otherWorld, double ox, double oy, double oz) {
        if (!world.equals(otherWorld)) {
            return Double.MAX_VALUE;
        }
        return (x - ox) * (x - ox) + (y - oy) * (y - oy) + (z - oz) * (z - oz);
    }
}
