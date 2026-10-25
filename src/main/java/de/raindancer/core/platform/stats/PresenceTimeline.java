package de.raindancer.core.platform.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * How many players were on at once, replayed from the audit journal's joins and quits — the same history the panel
 * draws, network-wide (the journal does not say which server). Read like the panel does: a quit and a join within a
 * minute, or a join and the old server's quit within seconds, are a server switch, not leaving; a join while still
 * counted as on, with no quit soon after, means the earlier quit was lost and starts afresh; a session with no word for
 * {@value #STALE_HOURS} hours is taken to have ended.
 */
public final class PresenceTimeline {

    /** A join or a quit, at epoch millis. */
    public record Event(long at, UUID player, boolean join) {
    }

    private record Step(long at, int online) {
    }

    /** The old server's quit after the new one's join. */
    private static final long JOIN_THEN_QUIT = 15_000;
    static final long STALE_HOURS = 12;
    private static final long STALE = STALE_HOURS * 3_600_000;

    private final List<Step> steps;
    private final List<Event> joins;

    private PresenceTimeline(List<Step> steps, List<Event> joins) {
        this.steps = steps;
        this.joins = joins;
    }

    public static PresenceTimeline of(List<Event> events) {
        return of(events, Long.MAX_VALUE);
    }

    /** @param now sessions silent for longer than {@value #STALE_HOURS} hours before it count as ended */
    public static PresenceTimeline of(List<Event> events, long now) {
        List<Event> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong(Event::at));
        List<Event> all = withStaleEnds(sorted, now);
        Map<UUID, Integer> held = new HashMap<>();
        Map<UUID, Long> lastJoin = new HashMap<>();
        List<Step> steps = new ArrayList<>();
        List<Event> joins = new ArrayList<>();
        int online = 0;
        for (Event event : all) {
            int count = held.getOrDefault(event.player(), 0);
            if (event.join()) {
                joins.add(event);
                if (count == 0) {
                    online++;
                }
                // On already: a switch if the old server's quit follows at once, else the earlier quit was lost.
                count = count == 0 ? 1 : 2;
                lastJoin.put(event.player(), event.at());
            } else if (count == 2 && event.at() - lastJoin.getOrDefault(event.player(), 0L) <= JOIN_THEN_QUIT) {
                count = 1;
            } else if (count > 0) {
                count = 0;
                online--;
            }
            held.put(event.player(), count);
            if (!steps.isEmpty() && steps.getLast().at() == event.at()) {
                steps.removeLast();
            }
            steps.add(new Step(event.at(), online));
        }
        return new PresenceTimeline(List.copyOf(steps), List.copyOf(joins));
    }

    /** A quit put in where a player had no word for too long after joining: their quit was lost. */
    private static List<Event> withStaleEnds(List<Event> sorted, long now) {
        Map<UUID, Event> open = new HashMap<>();
        List<Event> out = new ArrayList<>();
        for (Event event : sorted) {
            Event held = open.get(event.player());
            if (held != null && event.at() - held.at() > STALE) {
                out.add(new Event(held.at() + STALE, event.player(), false));
                open.remove(event.player());
            }
            out.add(event);
            if (event.join()) {
                open.put(event.player(), event);
            } else {
                open.remove(event.player());
            }
        }
        for (Event held : open.values()) {
            if (now - held.at() > STALE) {
                out.add(new Event(held.at() + STALE, held.player(), false));
            }
        }
        out.sort(Comparator.comparingLong(Event::at));
        return out;
    }

    /** Most players on at once, and when it was first reached; 0 players for an empty journal. */
    public StatsHistory.Record peak() {
        StatsHistory.Record best = new StatsHistory.Record(0, 0);
        for (Step step : steps) {
            if (step.online() > best.players()) {
                best = new StatsHistory.Record(step.online(), step.at());
            }
        }
        return best;
    }

    public int onlineAt(long at) {
        int online = 0;
        for (Step step : steps) {
            if (step.at() > at) {
                break;
            }
            online = step.online();
        }
        return online;
    }

    /** {@code count} columns from {@code from} to {@code to} (epoch millis), each the most on at once in its stretch. */
    public double[] columns(long from, long to, int count) {
        double[] out = new double[count];
        long span = Math.max(1, to - from);
        for (int i = 0; i < count; i++) {
            long start = from + span * i / count;
            long end = from + span * (i + 1) / count;
            int most = onlineAt(start);
            for (Step step : steps) {
                if (step.at() >= start && step.at() < end) {
                    most = Math.max(most, step.online());
                }
            }
            out[i] = most;
        }
        return out;
    }

    /** Different players who joined at {@code since} or later. */
    public int visitorsSince(long since) {
        Set<UUID> seen = new HashSet<>();
        for (Event join : joins) {
            if (join.at() >= since) {
                seen.add(join.player());
            }
        }
        return seen.size();
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }
}
