package de.raindancer.core.world.movement;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A parkour course — a start, checkpoints in order, a finish — and who is running it. Fed with where players move,
 * it says what just happened; the caller does the talking, the sounds, the teleport back and the records.
 * No Bukkit types, safe from any thread.
 *
 * <p>Touching the start (again) starts the clock (again). A later checkpoint counts even if an earlier one was
 * skipped, but the finish only counts with the last checkpoint reached. A run ends at the finish, when the runner
 * flies, glides, changes game mode or world (the caller says "not on foot"), or with {@link #stop}.
 */
public final class Parkour {

    /** How near a spot counts as reaching it, blocks. */
    public static final double REACH = 1.2;
    /** How far below the start a runner has fallen off. */
    public static final double FALLEN = 12;

    /** A place on the course, by world name. Written as one line: {@code world x y z}. */
    public record Spot(String world, double x, double y, double z) {

        public static Optional<Spot> parse(String line) {
            if (line == null) {
                return Optional.empty();
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 4) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Spot(parts[0], Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3])));
            } catch (NumberFormatException notANumber) {
                return Optional.empty();
            }
        }

        public String line() {
            return world + " " + x + " " + y + " " + z;
        }

        boolean near(String inWorld, double atX, double atY, double atZ) {
            double dx = atX - x;
            double dy = atY - y;
            double dz = atZ - z;
            return world.equals(inWorld) && dx * dx + dy * dy + dz * dz <= REACH * REACH * 2;
        }
    }

    /** The course; without a start or a finish there is none to run. */
    public record Course(Spot start, List<Spot> checkpoints, Spot finish) {
        public Course {
            checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
        }

        public boolean isComplete() {
            return start != null && finish != null;
        }
    }

    /** A run under way: when it started and the highest checkpoint reached (0 = none yet). */
    public record Run(long startedAt, int checkpoint) {
    }

    /** What a move meant. */
    public sealed interface Step {
        record None() implements Step {
        }

        record Started() implements Step {
        }

        /** Checkpoint {@code number}, counted from 1, newly reached. */
        record Checkpoint(int number) implements Step {
        }

        /** At the finish with every checkpoint; the run is over. */
        record Finished(long startedAt, long tookMillis) implements Step {
        }

        /** At the finish, but the last checkpoint is missing; the run goes on. */
        record MissedCheckpoint() implements Step {
        }

        /** Fallen off: back to {@code back}, the last checkpoint or the start. */
        record FellOff(Spot back) implements Step {
        }
    }

    private static final Step NONE = new Step.None();

    private final LongSupplier clock;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();

    public Parkour() {
        this(System::currentTimeMillis);
    }

    public Parkour(LongSupplier clock) {
        this.clock = clock;
    }

    public Step moved(UUID player, Course course, String world, double x, double y, double z, boolean onFoot) {
        if (course == null || !course.isComplete()) {
            return NONE;
        }
        if (!onFoot || !course.start().world().equals(world)) {
            runs.remove(player);
            return NONE;
        }
        if (course.start().near(world, x, y, z)) {
            boolean fresh = runs.put(player, new Run(clock.getAsLong(), 0)) == null;
            return fresh ? new Step.Started() : NONE;
        }
        Run run = runs.get(player);
        if (run == null) {
            return NONE;
        }
        List<Spot> checkpoints = course.checkpoints();
        for (int index = checkpoints.size() - 1; index >= 0; index--) {
            if (index + 1 > run.checkpoint() && checkpoints.get(index).near(world, x, y, z)) {
                runs.put(player, new Run(run.startedAt(), index + 1));
                return new Step.Checkpoint(index + 1);
            }
        }
        if (course.finish().near(world, x, y, z)) {
            if (run.checkpoint() < checkpoints.size()) {
                return new Step.MissedCheckpoint();
            }
            runs.remove(player);
            return new Step.Finished(run.startedAt(), clock.getAsLong() - run.startedAt());
        }
        if (y < course.start().y() - FALLEN) {
            return new Step.FellOff(backTo(run, course));
        }
        return NONE;
    }

    public Optional<Run> runOf(UUID player) {
        return Optional.ofNullable(runs.get(player));
    }

    /** Where a runner who fell goes back to; nothing when they are not running. */
    public Optional<Spot> respawn(UUID player, Course course) {
        Run run = runs.get(player);
        return run == null || course == null || !course.isComplete() ? Optional.empty()
                : Optional.of(backTo(run, course));
    }

    public void stop(UUID player) {
        runs.remove(player);
    }

    /** {@code m:ss.hh}, like a stopwatch. */
    public static String time(long millis) {
        Duration took = Duration.ofMillis(millis);
        return String.format(Locale.ROOT, "%d:%02d.%02d", took.toMinutes(), took.toSecondsPart(),
                took.toMillisPart() / 10);
    }

    private static Spot backTo(Run run, Course course) {
        return run.checkpoint() > 0 && run.checkpoint() <= course.checkpoints().size()
                ? course.checkpoints().get(run.checkpoint() - 1) : course.start();
    }
}
