package de.raindancer.core.world.movement;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParkourTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final Parkour parkour = new Parkour(now::get);
    private final UUID runner = UUID.randomUUID();
    private final Parkour.Course course = new Parkour.Course(
            new Parkour.Spot("hub", 0.5, 20, 0.5),
            List.of(new Parkour.Spot("hub", 10.5, 30, 0.5), new Parkour.Spot("hub", 20.5, 40, 0.5)),
            new Parkour.Spot("hub", 30.5, 50, 0.5));

    private Parkour.Step at(double x, double y, double z) {
        return parkour.moved(runner, course, "hub", x, y, z, true);
    }

    @Test
    void stepsOnTheStartStartTheClockAndTheFinishStopsIt() {
        assertInstanceOf(Parkour.Step.Started.class, at(0.5, 20, 0.5));
        assertEquals(new Parkour.Step.Checkpoint(1), at(10.5, 30, 0.5));
        assertEquals(new Parkour.Step.Checkpoint(2), at(20.5, 40, 0.5));
        now.addAndGet(12_345);
        assertEquals(new Parkour.Step.Finished(1_000, 12_345), at(30.5, 50, 0.5));
        assertTrue(parkour.runOf(runner).isEmpty());
    }

    @Test
    void theFinishWithoutTheLastCheckpointDoesNotCount() {
        at(0.5, 20, 0.5);
        at(10.5, 30, 0.5);
        assertInstanceOf(Parkour.Step.MissedCheckpoint.class, at(30.5, 50, 0.5));
        assertTrue(parkour.runOf(runner).isPresent());
    }

    @Test
    void nobodyRunningIsLeftAloneAtTheFinish() {
        assertInstanceOf(Parkour.Step.None.class, at(30.5, 50, 0.5));
    }

    @Test
    void fallingFarBelowTheStartGoesBackToTheLastCheckpoint() {
        at(0.5, 20, 0.5);
        at(10.5, 30, 0.5);
        Parkour.Step fell = at(5, 20 - Parkour.FALLEN - 1, 5);
        assertEquals(new Parkour.Step.FellOff(course.checkpoints().get(0)), fell);
    }

    @Test
    void flyingOrAnotherWorldEndsTheRun() {
        at(0.5, 20, 0.5);
        assertInstanceOf(Parkour.Step.None.class, parkour.moved(runner, course, "hub", 3, 25, 3, false));
        assertTrue(parkour.runOf(runner).isEmpty());
        at(0.5, 20, 0.5);
        assertInstanceOf(Parkour.Step.None.class, parkour.moved(runner, course, "other", 10.5, 30, 0.5, true));
    }

    @Test
    void anUnfinishedCourseDoesNothing() {
        Parkour.Course half = new Parkour.Course(course.start(), List.of(), null);
        assertInstanceOf(Parkour.Step.None.class, parkour.moved(runner, half, "hub", 0.5, 20, 0.5, true));
    }

    @Test
    void aSpotIsWrittenAndReadAsOneLine() {
        Parkour.Spot spot = new Parkour.Spot("world", -1.5, 84, 16.5);
        assertEquals("world -1.5 84.0 16.5", spot.line());
        assertEquals(Optional.of(spot), Parkour.Spot.parse(spot.line()));
        assertTrue(Parkour.Spot.parse("nonsense").isEmpty());
    }

    @Test
    void timesReadLikeAStopwatch() {
        assertEquals("1:02.34", Parkour.time(62_345));
    }
}
