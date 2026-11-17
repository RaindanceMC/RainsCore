package de.raindancer.core.ui.statue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;

/**
 * How statues stand: each on its own spot, or a ranking's three together as a podium. Who is where, how high, which
 * one is labelled, and who of a thank-you board stands while the others wait their turn.
 */
public final class StatueRule {

    private static final double SPACING = 1.0;
    /** Statues of one board within this many blocks share the label of the highest among them. */
    public static final double SHARED_LABEL_REACH = 6.0;

    /** How tall the pedestal under each place is, in blocks. */
    public double height(int place) {
        return switch (place) {
            case 1 -> 1.0;
            case 2 -> 0.7;
            default -> 0.45;
        };
    }

    /** How far above its ground block a statue's feet are: a ranking's pedestal, a thank-you's none. */
    public double standingHeight(StatueSpot spot, boolean thankYou) {
        return thankYou ? 0 : height(spot.place());
    }

    /** How high over the ground a statue's label floats: above its name tag, which the mannequin draws over its head. */
    public double labelHeight(int place) {
        return height(place) + 2.9;
    }

    /**
     * Where a place stands from the podium's centre, {x, y, z}, for statues facing {@code yaw}. Second goes on the
     * statues' right hand, so the crowd in front reads 2 · 1 · 3.
     */
    public double[] offset(int place, float yaw) {
        double side = switch (place) {
            case 1 -> 0;
            case 2 -> SPACING;
            default -> -SPACING;
        };
        double radians = Math.toRadians(yaw);
        // Minecraft's yaw 0 faces +z; the right hand then points to -x.
        return new double[]{-Math.cos(radians) * side, height(place), -Math.sin(radians) * side};
    }

    /** A whole podium of statues facing {@code yaw}, the winner on the ground at {@code x y z}. */
    public List<StatueSpot> podium(String world, double x, double y, double z, float yaw, String board, String group) {
        List<StatueSpot> three = new ArrayList<>();
        for (int place = 1; place <= StatueSpot.PODIUM; place++) {
            double[] offset = offset(place, yaw);
            StatueSpot spot = new StatueSpot(world, x + offset[0], y, z + offset[2], yaw, board, place, group);
            // Through the written form, so a spot read back from the store is equal to the one placed.
            three.add(StatueSpot.parse(spot.written()).orElseThrow());
        }
        return three;
    }

    /**
     * Every statue in some lines; a podium written before statues stood on their own
     * ({@code "casino 10.50 64.00 -3.25 90.0 casino"}) is read as its three. A line that is neither is skipped.
     */
    public List<StatueSpot> read(List<String> lines) {
        List<StatueSpot> spots = new ArrayList<>();
        for (String line : lines) {
            Optional<StatueSpot> spot = StatueSpot.parse(line);
            if (spot.isPresent()) {
                spots.add(spot.get());
            } else {
                spots.addAll(oldPodium(line));
            }
        }
        return spots;
    }

    /** Whether a line is a statue or an old podium. */
    public boolean readable(String line) {
        return StatueSpot.parse(line).isPresent() || !oldPodium(line).isEmpty();
    }

    /** The group follows from the old podium's line as Economy wrote it, so reading it twice agrees. */
    private List<StatueSpot> oldPodium(String line) {
        if (line == null) {
            return List.of();
        }
        String[] words = line.trim().split("\\s+");
        if (words.length != 6 || !StatueBoard.validId(words[5].toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        try {
            String world = words[0];
            double x = Double.parseDouble(words[1]);
            double y = Double.parseDouble(words[2]);
            double z = Double.parseDouble(words[3]);
            float yaw = Float.parseFloat(words[4]);
            String board = words[5].toLowerCase(Locale.ROOT);
            String key = String.format(Locale.ROOT, "%s %.2f %.2f %.2f %.1f %s", world, x, y, z, yaw, board);
            return podium(world, x, y, z, yaw, board, String.format(Locale.ROOT, "%08x", key.hashCode()));
        } catch (NumberFormatException notANumber) {
            return List.of();
        }
    }

    /**
     * Who a statue of {@code spots} shows: with no more people than statues each keeps one; with more, they take turns,
     * {@code turn} counting up; every turn the whole row moves on by {@code spots}, so over the turns everybody stands.
     */
    public <T> Optional<T> standing(List<T> people, int place, int spots, long turn) {
        if (place < 1 || people.isEmpty()) {
            return Optional.empty();
        }
        if (people.size() <= spots) {
            return place <= people.size() ? Optional.of(people.get(place - 1)) : Optional.empty();
        }
        long at = (place - 1 + Math.floorMod(turn, (long) people.size()) * spots) % people.size();
        return Optional.of(people.get((int) at));
    }

    /** The place a new statue of a thank-you board takes: the lowest free one of that board, none once all stand. */
    public OptionalInt nextPlace(List<StatueSpot> all, String board) {
        Set<Integer> taken = new HashSet<>();
        all.stream().filter(spot -> spot.board().equals(board)).forEach(spot -> taken.add(spot.place()));
        for (int place = 1; place <= StatueSpot.MOST; place++) {
            if (!taken.contains(place)) {
                return OptionalInt.of(place);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Whether a statue carries the label of what it ranks: of a podium the highest left; one on its own unless a
     * higher place of the same board stands within {@link #SHARED_LABEL_REACH} blocks, so statues put up side by side
     * share one label instead of stacking the same heading. A thank-you's never.
     */
    public boolean labelled(StatueSpot spot, List<StatueSpot> all, boolean thankYou) {
        if (thankYou) {
            return false;
        }
        if (!spot.standalone()) {
            return all.stream().noneMatch(other -> other.group().equals(spot.group()) && other.place() < spot.place());
        }
        double reach = SHARED_LABEL_REACH * SHARED_LABEL_REACH;
        return all.stream().noneMatch(other -> other.board().equals(spot.board()) && other.place() < spot.place()
                && other.distanceSquared(spot.world(), spot.x(), spot.y(), spot.z()) <= reach);
    }

    /** {@code yaw} turned to the nearest multiple of {@code step}, between 0 and 360. */
    public float facing(float yaw, float step) {
        float turned = Math.round(yaw / step) * step;
        return ((turned % 360) + 360) % 360;
    }

    /** The statue nearest to a point, closer than {@code maxSquared} (squared blocks), among those that match. */
    public Optional<StatueSpot> nearest(List<StatueSpot> all, String world, double x, double y, double z,
                                        double maxSquared, Predicate<StatueSpot> which) {
        StatueSpot nearest = null;
        double best = maxSquared;
        for (StatueSpot spot : all) {
            double distance = spot.distanceSquared(world, x, y, z);
            if (which.test(spot) && distance < best) {
                best = distance;
                nearest = spot;
            }
        }
        return Optional.ofNullable(nearest);
    }
}
