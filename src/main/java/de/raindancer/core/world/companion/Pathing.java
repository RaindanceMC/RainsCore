package de.raindancer.core.world.companion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A short way through the blocks around a companion (A* over a few dozen blocks): a walker around walls, up steps,
 * around holes it would not climb out of and away from lava, fire and deep water; a flyer around walls and out of
 * ceilings. Blocks are only asked through {@link Terrain}, so the search knows nothing of Paper.
 */
public final class Pathing {

    /** What a block is to a companion. */
    public enum Ground {
        /** Air, grass, a flower: it goes through. */
        OPEN,
        /** It stands on it and cannot go through. */
        SOLID,
        /** Water: wading where it is shallow, never swimming. */
        WATER,
        /** Lava, fire, magma, cactus, berry bushes, powder snow: never on it or in it. */
        HARMFUL
    }

    public interface Terrain {
        Ground at(int x, int y, int z);
    }

    public record Cell(int x, int y, int z) {

        Steering.Vec centre() {
            return new Steering.Vec(x + 0.5, y, z + 0.5);
        }
    }

    /**
     * @param cells    the way, without the cell it starts in
     * @param complete whether it ends at the goal; if not, it ends as near as it could get
     */
    public record Route(List<Cell> cells, boolean complete, boolean flies) {
    }

    /** Further than this from the start, nothing is looked at. */
    static final int BOUND = 32;
    private static final int LONGEST_DROP = 3;
    private static final int[][] FLAT = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private Pathing() {
    }

    /**
     * A walker's way.
     *
     * @param height blocks of room it needs above its feet
     * @param reach  how near the goal (flat, in blocks) is near enough
     * @param budget most cells looked at
     */
    public static Route walk(Terrain terrain, Cell from, Cell to, int height, double reach, int budget) {
        return search(new Cached(terrain), from, to, Math.max(1, height), reach, budget, false);
    }

    /** A flyer's way, through the air. */
    public static Route fly(Terrain terrain, Cell from, Cell to, int height, double reach, int budget) {
        return search(new Cached(terrain), from, to, Math.max(1, height), reach, budget, true);
    }

    private record Node(Cell cell, double cost, double estimate) {
    }

    private static Route search(Terrain terrain, Cell from, Cell to, int height, double reach, int budget,
                                boolean flies) {
        Map<Cell, Cell> cameFrom = new HashMap<>();
        Map<Cell, Double> costs = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.cost() + a.estimate(),
                b.cost() + b.estimate()));
        costs.put(from, 0.0);
        open.add(new Node(from, 0, estimate(from, to)));
        Cell best = from;
        double bestLeft = estimate(from, to);
        int looked = 0;
        while (!open.isEmpty() && looked < budget) {
            Node node = open.poll();
            Cell cell = node.cell();
            if (node.cost() > costs.getOrDefault(cell, Double.MAX_VALUE)) {
                continue;
            }
            looked++;
            if (arrived(cell, to, reach, flies)) {
                return new Route(trace(cameFrom, from, cell), true, flies);
            }
            double left = estimate(cell, to);
            if (left < bestLeft) {
                bestLeft = left;
                best = cell;
            }
            for (Step step : flies ? flights(terrain, cell, height) : walks(terrain, cell, height)) {
                Cell next = step.cell();
                if (Math.abs(next.x() - from.x()) > BOUND || Math.abs(next.z() - from.z()) > BOUND
                        || Math.abs(next.y() - from.y()) > BOUND) {
                    continue;
                }
                double cost = node.cost() + step.cost();
                if (cost < costs.getOrDefault(next, Double.MAX_VALUE)) {
                    costs.put(next, cost);
                    cameFrom.put(next, cell);
                    open.add(new Node(next, cost, estimate(next, to)));
                }
            }
        }
        return new Route(trace(cameFrom, from, best), false, flies);
    }

    private static boolean arrived(Cell cell, Cell to, double reach, boolean flies) {
        double flat = Math.hypot(cell.x() - to.x(), cell.z() - to.z());
        int rise = Math.abs(cell.y() - to.y());
        return flies ? Math.sqrt(flat * flat + rise * rise) <= reach : flat <= reach && rise <= 1;
    }

    /** Octile on the flat, plus the climb. */
    private static double estimate(Cell a, Cell b) {
        double dx = Math.abs(a.x() - b.x());
        double dz = Math.abs(a.z() - b.z());
        return Math.max(dx, dz) + (Math.sqrt(2) - 1) * Math.min(dx, dz) + Math.abs(a.y() - b.y());
    }

    private static List<Cell> trace(Map<Cell, Cell> cameFrom, Cell from, Cell end) {
        List<Cell> way = new ArrayList<>();
        for (Cell at = end; at != null && !at.equals(from); at = cameFrom.get(at)) {
            way.add(at);
        }
        return way.reversed();
    }

    private record Step(Cell cell, double cost) {
    }

    // ------------------------------------------------------------------ walking

    private static List<Step> walks(Terrain terrain, Cell at, int height) {
        List<Step> steps = new ArrayList<>(12);
        boolean canJump = room(terrain, at.x(), at.y() + height, at.z(), 1);
        for (int[] d : FLAT) {
            int x = at.x() + d[0];
            int z = at.z() + d[1];
            boolean diagonal = d[0] != 0 && d[1] != 0;
            if (diagonal && (!room(terrain, at.x() + d[0], at.y(), at.z(), height)
                    || !room(terrain, at.x(), at.y(), at.z() + d[1], height))) {
                continue;
            }
            double length = diagonal ? Math.sqrt(2) : 1;
            if (stands(terrain, x, at.y(), z, height)) {
                steps.add(new Step(new Cell(x, at.y(), z), length + penalty(terrain, x, at.y(), z)));
            } else if (!room(terrain, x, at.y(), z, height)) {
                if (canJump && !diagonal && stands(terrain, x, at.y() + 1, z, height)) {
                    steps.add(new Step(new Cell(x, at.y() + 1, z), length + 0.6 + penalty(terrain, x, at.y() + 1, z)));
                }
            } else {
                for (int drop = 1; drop <= LONGEST_DROP; drop++) {
                    int y = at.y() - drop;
                    if (stands(terrain, x, y, z, height)) {
                        steps.add(new Step(new Cell(x, y, z), length + 0.3 * drop + penalty(terrain, x, y, z)));
                        break;
                    }
                    if (!room(terrain, x, y, z, 1)) {
                        break;
                    }
                }
            }
        }
        return steps;
    }

    /** Room for its body, and something to stand on that is not deep water or harm. */
    private static boolean stands(Terrain terrain, int x, int y, int z, int height) {
        if (!room(terrain, x, y, z, height)) {
            return false;
        }
        if (terrain.at(x, y, z) == Ground.WATER && terrain.at(x, y + 1, z) == Ground.WATER) {
            return false;
        }
        return terrain.at(x, y - 1, z) == Ground.SOLID;
    }

    /** Open (or water) for {@code height} blocks upwards, nothing harmful. */
    private static boolean room(Terrain terrain, int x, int y, int z, int height) {
        for (int i = 0; i < height; i++) {
            Ground ground = terrain.at(x, y + i, z);
            if (ground == Ground.SOLID || ground == Ground.HARMFUL) {
                return false;
            }
        }
        return true;
    }

    /** Wading costs, and so does walking beside something that burns. */
    private static double penalty(Terrain terrain, int x, int y, int z) {
        double extra = terrain.at(x, y, z) == Ground.WATER ? 2 : 0;
        for (int[] d : FLAT) {
            if (terrain.at(x + d[0], y, z + d[1]) == Ground.HARMFUL
                    || terrain.at(x + d[0], y - 1, z + d[1]) == Ground.HARMFUL) {
                return extra + 2;
            }
        }
        return extra;
    }

    // ------------------------------------------------------------------ flying

    private static List<Step> flights(Terrain terrain, Cell at, int height) {
        List<Step> steps = new ArrayList<>(18);
        for (int dy = -1; dy <= 1; dy++) {
            if (dy != 0 && !air(terrain, at.x(), at.y() + dy, at.z(), height)) {
                continue;
            }
            if (dy != 0) {
                steps.add(new Step(new Cell(at.x(), at.y() + dy, at.z()), 1));
            }
            for (int[] d : FLAT) {
                int x = at.x() + d[0];
                int z = at.z() + d[1];
                int y = at.y() + dy;
                boolean diagonal = d[0] != 0 && d[1] != 0;
                if (!air(terrain, x, y, z, height) || (dy != 0 && !air(terrain, x, at.y(), z, height))) {
                    continue;
                }
                if (diagonal && (!air(terrain, at.x() + d[0], y, at.z(), height)
                        || !air(terrain, at.x(), y, at.z() + d[1], height))) {
                    continue;
                }
                steps.add(new Step(new Cell(x, y, z), (diagonal ? Math.sqrt(2) : 1) + (dy != 0 ? 0.4 : 0)));
            }
        }
        return steps;
    }

    private static boolean air(Terrain terrain, int x, int y, int z, int height) {
        for (int i = 0; i < height; i++) {
            if (terrain.at(x, y + i, z) != Ground.OPEN) {
                return false;
            }
        }
        return true;
    }

    /** Each block asked once per search. */
    private static final class Cached implements Terrain {

        private final Terrain terrain;
        private final Map<Cell, Ground> known = new HashMap<>();

        Cached(Terrain terrain) {
            this.terrain = terrain;
        }

        @Override
        public Ground at(int x, int y, int z) {
            return known.computeIfAbsent(new Cell(x, y, z), cell -> terrain.at(x, y, z));
        }
    }

    // ------------------------------------------------------------------ following one

    /** A route being walked: where to head next from where it is. */
    public static final class Course {

        private static final double PASSED = 0.45;
        private static final int LOOK_AHEAD = 4;

        private final Route route;
        private final Steering.Vec goal;
        private int index;

        public Course(Route route, Steering.Vec goal) {
            this.route = route;
            this.goal = goal;
        }

        public Route route() {
            return route;
        }

        /** The next cell's centre to head for; null once it has walked it all. */
        public Steering.Vec next(Steering.Vec here) {
            List<Cell> cells = route.cells();
            int nearest = index;
            double nearestDistance = Double.MAX_VALUE;
            for (int i = index; i < Math.min(cells.size(), index + LOOK_AHEAD); i++) {
                double distance = distance(cells.get(i).centre(), here);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = i;
                }
            }
            index = nearestDistance < PASSED ? nearest + 1 : nearest;
            return index < cells.size() ? cells.get(index).centre() : null;
        }

        /** Whether it still leads where it should go. */
        public boolean leadsTo(Steering.Vec wanted, double slack) {
            return Math.hypot(goal.x() - wanted.x(), goal.z() - wanted.z()) <= slack
                    && Math.abs(goal.y() - wanted.y()) <= slack + 1;
        }

        public boolean done() {
            return index >= route.cells().size();
        }

        private double distance(Steering.Vec cell, Steering.Vec here) {
            double flat = Math.hypot(cell.x() - here.x(), cell.z() - here.z());
            return route.flies() ? Math.hypot(flat, cell.y() - here.y()) : flat;
        }
    }
}
