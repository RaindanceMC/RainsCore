package de.raindancer.core.moderation.players;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Everybody's ping over the last hour on this server, sampled by Core every {@link #EVERY} — so "how bad is it,
 * really" has an answer beyond the one number the game shows right now. Kept until somebody's last sample is an
 * hour old, so it can still be read after they left. Only in memory (a restart forgets it) and not shared across
 * the network. Thread-safe.
 */
public final class PingHistory {

    public static final Duration EVERY = Duration.ofSeconds(5);
    public static final Duration KEPT = Duration.ofHours(1);

    public record Sample(long at, int ms) {
    }

    /**
     * One player's pings over a window.
     *
     * @param jitter the average change from one sample to the next — high means a shaky line, whatever the average
     */
    public record Summary(String name, long from, long until, List<Sample> all, int latest, long latestAt,
                          int min, int average, int median, int p95, int max, int jitter) {

        public int samples() {
            return all.size();
        }

        /** From the first to the last sample in the window. */
        public Duration covered() {
            return Duration.ofMillis(all.getLast().at() - all.getFirst().at());
        }

        /** The window cut into {@code count} equal slices, oldest first: each slice's average, or -1 without samples. */
        public int[] columns(int count) {
            long[] sums = new long[count];
            int[] counts = new int[count];
            long width = Math.max(1, until - from);
            for (Sample sample : all) {
                int column = (int) Math.min(count - 1, Math.max(0, (sample.at() - from) * count / width));
                sums[column] += sample.ms();
                counts[column]++;
            }
            int[] columns = new int[count];
            for (int i = 0; i < count; i++) {
                columns[i] = counts[i] == 0 ? -1 : (int) Math.round((double) sums[i] / counts[i]);
            }
            return columns;
        }
    }

    private static final class Track {
        volatile String name;
        final Deque<Sample> samples = new ArrayDeque<>();

        Track(String name) {
            this.name = name;
        }
    }

    private final LongSupplier clock;
    private final Map<UUID, Track> tracks = new ConcurrentHashMap<>();

    public PingHistory(LongSupplier clock) {
        this.clock = clock;
    }

    /** One reading; a negative ping (not measured yet) is ignored. */
    public void record(UUID player, String name, int ms) {
        if (player == null || ms < 0) {
            return;
        }
        long now = clock.getAsLong();
        Track track = tracks.computeIfAbsent(player, key -> new Track(name));
        synchronized (track) {
            if (name != null) {
                track.name = name;
            }
            track.samples.addLast(new Sample(now, ms));
            dropOld(track, now);
        }
    }

    /** Whose history goes by this name (any case), if anybody's is still kept. */
    public Optional<UUID> recentlySeen(String name) {
        if (name == null) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        String wanted = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<UUID, Track> entry : tracks.entrySet()) {
            Track track = entry.getValue();
            synchronized (track) {
                dropOld(track, now);
                if (!track.samples.isEmpty() && track.name != null && track.name.toLowerCase(Locale.ROOT).equals(wanted)) {
                    return Optional.of(entry.getKey());
                }
            }
        }
        return Optional.empty();
    }

    /** The samples of the last {@code window} (at most {@link #KEPT}), or empty with none in it. */
    public Optional<Summary> summary(UUID player, Duration window) {
        Track track = player == null ? null : tracks.get(player);
        if (track == null) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        long from = now - Math.min(window.toMillis(), KEPT.toMillis());
        List<Sample> inside = new ArrayList<>();
        String name;
        synchronized (track) {
            dropOld(track, now);
            name = track.name;
            for (Sample sample : track.samples) {
                if (sample.at() >= from) {
                    inside.add(sample);
                }
            }
        }
        if (inside.isEmpty()) {
            return Optional.empty();
        }
        int[] sorted = inside.stream().mapToInt(Sample::ms).sorted().toArray();
        long sum = Arrays.stream(sorted).asLongStream().sum();
        long steps = 0;
        for (int i = 1; i < inside.size(); i++) {
            steps += Math.abs(inside.get(i).ms() - inside.get(i - 1).ms());
        }
        Sample last = inside.getLast();
        return Optional.of(new Summary(name, from, now, List.copyOf(inside), last.ms(), last.at(),
                sorted[0], (int) Math.round((double) sum / sorted.length), percentile(sorted, 50),
                percentile(sorted, 95), sorted[sorted.length - 1],
                inside.size() < 2 ? 0 : (int) Math.round((double) steps / (inside.size() - 1))));
    }

    /** Forgets everybody whose last sample is older than {@link #KEPT}. */
    public void prune() {
        long now = clock.getAsLong();
        tracks.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                dropOld(entry.getValue(), now);
                return entry.getValue().samples.isEmpty();
            }
        });
    }

    private static void dropOld(Track track, long now) {
        long oldest = now - KEPT.toMillis();
        while (!track.samples.isEmpty() && track.samples.peekFirst().at() < oldest) {
            track.samples.removeFirst();
        }
    }

    /** Nearest rank: the smallest value at least {@code percent} of the samples are at or below. */
    private static int percentile(int[] sorted, int percent) {
        int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }
}
