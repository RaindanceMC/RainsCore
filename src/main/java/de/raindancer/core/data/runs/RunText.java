package de.raindancer.core.data.runs;

import de.raindancer.core.ui.text.Text;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/** How a run reads on a screen or in chat: times like a speedrun timer, places, gaps, who played. */
public final class RunText {

    private RunText() {
    }

    /** {@code 1:23.456}, or {@code 1:02:03.004} past an hour. */
    public static String clock(Duration time) {
        if (time == null) {
            return "-";
        }
        long millis = Math.abs(time.toMillis());
        long hours = millis / 3_600_000;
        long minutes = millis / 60_000 % 60;
        long seconds = millis / 1000 % 60;
        long rest = millis % 1000;
        String sign = time.isNegative() ? "-" : "";
        return hours > 0
                ? String.format(Locale.ROOT, "%s%d:%02d:%02d.%03d", sign, hours, minutes, seconds, rest)
                : String.format(Locale.ROOT, "%s%d:%02d.%03d", sign, minutes, seconds, rest);
    }

    /**
     * A difference, signed: {@code -1.500} ahead, {@code +1:01.000} behind for a time; {@code +3} for a
     * count. Seconds stay bare under a minute, the way a split timer shows them.
     */
    public static String gap(long difference, boolean isTime) {
        if (!isTime) {
            return (difference > 0 ? "+" : difference < 0 ? "-" : "±") + Math.abs(difference);
        }
        String sign = difference > 0 ? "+" : difference < 0 ? "-" : "±";
        long millis = Math.abs(difference);
        if (millis < 60_000) {
            return String.format(Locale.ROOT, "%s%d.%03d", sign, millis / 1000, millis % 1000);
        }
        return sign + clock(Duration.ofMillis(millis));
    }

    /** 1st, 2nd, 3rd, 4th … 11th, 12th, 13th … 21st. */
    public static String ordinal(int place) {
        int lastTwo = Math.abs(place) % 100;
        if (lastTwo >= 11 && lastTwo <= 13) {
            return place + "th";
        }
        return switch (Math.abs(place) % 10) {
            case 1 -> place + "st";
            case 2 -> place + "nd";
            case 3 -> place + "rd";
            default -> place + "th";
        };
    }

    /** Who played, by the names they had then: "Alex", "Alex & Sam", "Alex, Sam & Kim". */
    public static String who(Run run) {
        List<String> names = new ArrayList<>(run.players().values());
        names.removeIf(String::isBlank);
        if (names.isEmpty()) {
            return "nobody";
        }
        if (names.size() == 1) {
            return names.getFirst();
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " & " + names.getLast();
    }

    /**
     * The score as it is usually read: a time for a run where lower wins, a number otherwise. A game
     * whose lower-wins score is a count rather than a time (strokes, deaths) shows {@link #count}.
     */
    public static String score(Run run) {
        return run.lowerWins() ? clock(run.time()) : count(run);
    }

    /** The score as a plain number. */
    public static String count(Run run) {
        return String.valueOf(run.score());
    }

    /**
     * A leaderboard as chat lines, MiniMessage, best first: place, who, score — the reader's own runs
     * marked. Names are always shown as text, whatever they contain.
     */
    public static List<String> boardLines(List<Run> board, UUID reader, Function<Run, String> shown) {
        if (board.isEmpty()) {
            return List.of("<gray>No runs yet.");
        }
        List<String> lines = new ArrayList<>(board.size());
        int place = 0;
        for (Run run : board) {
            place++;
            String colour = switch (place) {
                case 1 -> "<gold>";
                case 2 -> "<white>";
                case 3 -> "<#c87533>";
                default -> "<gray>";
            };
            boolean mine = reader != null && run.includes(reader);
            lines.add(colour + ordinal(place) + " <white>" + Text.literal(who(run)) + "<dark_gray>: "
                    + colour + Text.literal(shown.apply(run)) + (mine ? " <green>(you)" : ""));
        }
        return lines;
    }
}
