package de.raindancer.core.world.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("how a companion finds its way")
class PathingTest {

    /** A flat world: grass at y 63, air above, plus whatever a test builds. */
    private static final class Grid implements Pathing.Terrain {

        private final Map<Pathing.Cell, Pathing.Ground> built = new HashMap<>();

        Grid set(int x, int y, int z, Pathing.Ground ground) {
            built.put(new Pathing.Cell(x, y, z), ground);
            return this;
        }

        Grid wall(int x, int fromZ, int toZ, int height) {
            for (int z = fromZ; z <= toZ; z++) {
                for (int y = 64; y < 64 + height; y++) {
                    set(x, y, z, Pathing.Ground.SOLID);
                }
            }
            return this;
        }

        @Override
        public Pathing.Ground at(int x, int y, int z) {
            Pathing.Ground ground = built.get(new Pathing.Cell(x, y, z));
            if (ground != null) {
                return ground;
            }
            return y <= 63 ? Pathing.Ground.SOLID : Pathing.Ground.OPEN;
        }
    }

    private static final Pathing.Cell START = new Pathing.Cell(0, 64, 0);

    @Test
    @DisplayName("over open ground it walks straight there")
    void straight() {
        Pathing.Route route = Pathing.walk(new Grid(), START, new Pathing.Cell(6, 64, 0), 1, 1, 500);
        assertThat(route.complete()).isTrue();
        assertThat(route.cells()).allMatch(cell -> cell.z() == 0 && cell.y() == 64);
        assertThat(route.cells().getLast().x()).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("a wall too high to hop is walked around")
    void aroundAWall() {
        Grid grid = new Grid().wall(3, -4, 4, 2);
        Pathing.Route route = Pathing.walk(grid, START, new Pathing.Cell(6, 64, 0), 1, 1, 2000);
        assertThat(route.complete()).isTrue();
        assertThat(route.cells()).noneMatch(cell -> cell.x() == 3 && Math.abs(cell.z()) <= 4);
        assertThat(route.cells()).anyMatch(cell -> Math.abs(cell.z()) >= 5);
    }

    @Test
    @DisplayName("a step of one block is climbed, a wall of two is not")
    void steps() {
        Grid stairs = new Grid().wall(2, -20, 20, 1).set(4, 64, 0, Pathing.Ground.SOLID)
                .set(4, 65, 0, Pathing.Ground.SOLID);
        for (int x = 3; x <= 8; x++) {
            for (int z = -20; z <= 20; z++) {
                if (stairs.at(x, 64, z) == Pathing.Ground.OPEN) {
                    stairs.set(x, 64, z, Pathing.Ground.SOLID);
                }
            }
        }
        Pathing.Route route = Pathing.walk(stairs, START, new Pathing.Cell(6, 65, 0), 1, 1, 2000);
        assertThat(route.complete()).isTrue();
        assertThat(route.cells()).anyMatch(cell -> cell.y() == 65);
    }

    @Test
    @DisplayName("lava, fire and deep water are gone around, never through")
    void dangers() {
        Grid grid = new Grid();
        for (int z = -2; z <= 2; z++) {
            grid.set(3, 63, z, Pathing.Ground.HARMFUL);
            grid.set(4, 64, z, Pathing.Ground.HARMFUL);
            grid.set(5, 63, z, Pathing.Ground.WATER).set(5, 64, z, Pathing.Ground.WATER);
        }
        Pathing.Route route = Pathing.walk(grid, START, new Pathing.Cell(8, 64, 0), 1, 1, 3000);
        assertThat(route.complete()).isTrue();
        assertThat(route.cells()).noneMatch(cell -> cell.x() >= 3 && cell.x() <= 5 && Math.abs(cell.z()) <= 2);
    }

    @Test
    @DisplayName("a hole deeper than a short drop is gone around")
    void holes() {
        Grid grid = new Grid();
        for (int x = 2; x <= 4; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 55; y <= 63; y++) {
                    grid.set(x, y, z, Pathing.Ground.OPEN);
                }
            }
        }
        Pathing.Route route = Pathing.walk(grid, START, new Pathing.Cell(7, 64, 0), 1, 1, 3000);
        assertThat(route.complete()).isTrue();
        assertThat(route.cells()).allMatch(cell -> cell.y() == 64);
    }

    @Test
    @DisplayName("it does not cut a corner through a wall's edge")
    void corners() {
        Grid grid = new Grid().set(1, 64, 0, Pathing.Ground.SOLID).set(1, 65, 0, Pathing.Ground.SOLID)
                .set(0, 64, 1, Pathing.Ground.SOLID).set(0, 65, 1, Pathing.Ground.SOLID);
        Pathing.Route route = Pathing.walk(grid, START, new Pathing.Cell(1, 64, 1), 1, 0, 500);
        assertThat(route.complete()).isTrue();
        assertThat(route.cells().getFirst()).isNotEqualTo(new Pathing.Cell(1, 64, 1));
        assertThat(route.cells().size()).isGreaterThan(2);
    }

    @Test
    @DisplayName("walled in, it gets as near as it can and says the way is not whole")
    void unreachable() {
        Grid grid = new Grid();
        for (int x = 4; x <= 8; x++) {
            for (int z = -2; z <= 2; z++) {
                boolean edge = x == 4 || x == 8 || Math.abs(z) == 2;
                if (edge) {
                    grid.set(x, 64, z, Pathing.Ground.SOLID).set(x, 65, z, Pathing.Ground.SOLID);
                }
            }
        }
        Pathing.Route route = Pathing.walk(grid, START, new Pathing.Cell(6, 64, 0), 1, 1, 3000);
        assertThat(route.complete()).isFalse();
        assertThat(route.cells()).isNotEmpty();
        assertThat(route.cells().getLast().x()).isEqualTo(3);
    }

    @Test
    @DisplayName("a taller companion needs room for its head")
    void headroom() {
        Grid grid = new Grid();
        for (int z = -1; z <= 1; z++) {
            grid.set(3, 65, z, Pathing.Ground.SOLID);
        }
        Pathing.Route low = Pathing.walk(grid, START, new Pathing.Cell(6, 64, 0), 1, 0, 2000);
        Pathing.Route tall = Pathing.walk(grid, START, new Pathing.Cell(6, 64, 0), 2, 0, 2000);
        assertThat(low.cells()).contains(new Pathing.Cell(3, 64, 0));
        assertThat(tall.cells()).noneMatch(cell -> cell.x() == 3 && Math.abs(cell.z()) <= 1);
    }

    @Test
    @DisplayName("a flyer goes over a wall it could never walk past, and keeps out of the ceiling")
    void flying() {
        Grid grid = new Grid();
        for (int z = -20; z <= 20; z++) {
            for (int y = 64; y <= 67; y++) {
                grid.set(3, y, z, Pathing.Ground.SOLID);
            }
        }
        Pathing.Route route = Pathing.fly(grid, new Pathing.Cell(0, 65, 0), new Pathing.Cell(6, 65, 0), 1, 1, 3000);
        assertThat(route.complete()).isTrue();
        assertThat(route.cells()).anyMatch(cell -> cell.y() >= 68);
        assertThat(route.cells()).allMatch(cell -> grid.at(cell.x(), cell.y(), cell.z()) == Pathing.Ground.OPEN);
    }

    @Test
    @DisplayName("a course is followed cell by cell, and a cell it stands on is passed")
    void course() {
        Pathing.Route route = Pathing.walk(new Grid(), START, new Pathing.Cell(4, 64, 0), 1, 0, 500);
        Pathing.Course course = new Pathing.Course(route, new Steering.Vec(4.5, 64, 0.5));
        Steering.Vec first = course.next(new Steering.Vec(0.5, 64, 0.5));
        assertThat(first.x()).isEqualTo(1.5);
        assertThat(first.z()).isEqualTo(0.5);
        Steering.Vec later = course.next(new Steering.Vec(2.45, 64, 0.5));
        assertThat(later.x()).isGreaterThanOrEqualTo(3.5);
        assertThat(course.next(new Steering.Vec(4.5, 64, 0.5))).isNull();
    }

    @Test
    @DisplayName("a course is stale once its goal has moved away")
    void stale() {
        Pathing.Course course = new Pathing.Course(Pathing.walk(new Grid(), START, new Pathing.Cell(4, 64, 0), 1, 0,
                500), new Steering.Vec(4.5, 64, 0.5));
        assertThat(course.leadsTo(new Steering.Vec(5, 64, 0.5), 1.5)).isTrue();
        assertThat(course.leadsTo(new Steering.Vec(8, 64, 0.5), 1.5)).isFalse();
    }
}
