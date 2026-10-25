package de.raindancer.core.platform.stats;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * The server's last day, a sample a minute — ticks, memory, processor, players — with the record of players at once
 * and who came today. Plain data, thread-safe; {@link ServerStats} fills it and keeps it across restarts.
 */
public final class StatsHistory {

    /** A day of minutes. */
    public static final int MINUTES = 24 * 60;

    /**
     * One minute of the server.
     *
     * @param minute  minutes since the epoch
     * @param tps     ticks per second over the last minute; NaN where the server cannot say (Folia)
     * @param mspt    milliseconds an average tick took; NaN where unknown
     * @param ramUsed megabytes of heap in use
     * @param ramMax  megabytes of heap there can be
     * @param cpu     percent of the processor the server takes; NaN where unknown
     */
    public record Sample(long minute, double tps, double mspt, long ramUsed, long ramMax, double cpu, int players) {

        public double ramPercent() {
            return ramMax <= 0 ? Double.NaN : ramUsed * 100.0 / ramMax;
        }
    }

    /** Most players on at once, and when. */
    public record Record(int players, long at) {
    }

    /** How a column of a chart sums up the minutes in it. */
    public enum Mode { AVERAGE, HIGHEST, LOWEST }

    private final List<Sample> samples = new ArrayList<>();
    private Record record = new Record(0, 0);
    private LocalDate day;
    private final Set<UUID> today = new HashSet<>();

    public synchronized void add(Sample sample) {
        if (!samples.isEmpty() && samples.getLast().minute() == sample.minute()) {
            samples.removeLast();
        }
        samples.add(sample);
        while (samples.size() > MINUTES) {
            samples.removeFirst();
        }
    }

    public synchronized List<Sample> samples() {
        return List.copyOf(samples);
    }

    public synchronized Sample latest() {
        return samples.isEmpty() ? null : samples.getLast();
    }

    /**
     * {@code count} columns from minute {@code from} (inclusive) to {@code to} (exclusive), each summing up its
     * stretch; NaN where nothing was measured.
     */
    public synchronized double[] columns(ToDoubleFunction<Sample> value, long from, long to, int count, Mode mode) {
        double[] sum = new double[count];
        int[] seen = new int[count];
        double[] out = new double[count];
        java.util.Arrays.fill(out, Double.NaN);
        long span = Math.max(1, to - from);
        for (Sample sample : samples) {
            if (sample.minute() < from || sample.minute() >= to) {
                continue;
            }
            double v = value.applyAsDouble(sample);
            if (Double.isNaN(v)) {
                continue;
            }
            int column = (int) Math.min(count - 1, (sample.minute() - from) * count / span);
            seen[column]++;
            switch (mode) {
                case AVERAGE -> sum[column] += v;
                case HIGHEST -> out[column] = Double.isNaN(out[column]) ? v : Math.max(out[column], v);
                case LOWEST -> out[column] = Double.isNaN(out[column]) ? v : Math.min(out[column], v);
            }
        }
        if (mode == Mode.AVERAGE) {
            for (int i = 0; i < count; i++) {
                out[i] = seen[i] == 0 ? Double.NaN : sum[i] / seen[i];
            }
        }
        return out;
    }

    /** Most players at once from minute {@code from} on; 0 for none measured. */
    public synchronized int peakSince(long from) {
        return samples.stream().filter(sample -> sample.minute() >= from).mapToInt(Sample::players).max().orElse(0);
    }

    /** Tells it how many are on now: the record goes up if this beats it. */
    public synchronized void online(int players, long at) {
        if (players > record.players()) {
            record = new Record(players, at);
        }
    }

    public synchronized Record record() {
        return record;
    }

    public synchronized void record(Record kept) {
        if (kept != null && kept.players() > record.players()) {
            record = kept;
        }
    }

    public synchronized void joined(UUID player, LocalDate on) {
        if (!on.equals(day)) {
            day = on;
            today.clear();
        }
        today.add(player);
    }

    /** How many different players came on {@code on}; 0 for any other day than the one being counted. */
    public synchronized int playersOn(LocalDate on) {
        return on.equals(day) ? today.size() : 0;
    }

    public synchronized LocalDate day() {
        return day;
    }

    public synchronized Set<UUID> today() {
        return Set.copyOf(today);
    }

    public synchronized List<String> lines() {
        return samples.stream().map(sample -> String.format(Locale.ROOT, "%d %s %s %d %d %s %d", sample.minute(),
                number(sample.tps()), number(sample.mspt()), sample.ramUsed(), sample.ramMax(), number(sample.cpu()),
                sample.players())).toList();
    }

    /** Reads one line {@link #lines} wrote; a garbled one is left out. */
    public void read(String line) {
        if (line == null) {
            return;
        }
        String[] parts = line.trim().split(" ");
        if (parts.length != 7) {
            return;
        }
        try {
            add(new Sample(Long.parseLong(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Long.parseLong(parts[3]), Long.parseLong(parts[4]), Double.parseDouble(parts[5]),
                    Integer.parseInt(parts[6])));
        } catch (NumberFormatException garbled) {
            // left out
        }
    }

    private static String number(double value) {
        return Double.isNaN(value) ? "NaN" : String.format(Locale.ROOT, "%.2f", value);
    }
}
