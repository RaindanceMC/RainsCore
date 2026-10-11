package de.raindancer.core.world.time;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moments and days as staff type them — "2026-10-10 17:30", "10.10.2026", "today", "9:05", "last 2h" — read
 * against one zone's calendar, so "yesterday" is the server's yesterday rather than UTC's.
 *
 * <p>As the end of a range, the whole unit typed is included: "until 18:00" takes in the minute 18:00, a date
 * takes in the whole day. A range is always {@code [from, to)}.
 */
public final class Moments {

    /** A stretch of time, from inclusive, to exclusive. */
    public record Span(Instant from, Instant to) {
        public boolean contains(Instant moment) {
            return !moment.isBefore(from) && moment.isBefore(to);
        }
    }

    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern GERMAN_DATE = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})?");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern SEPARATOR = Pattern.compile("[ t_@]");
    private static final DateTimeFormatter WRITTEN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Moments() {
    }

    /** Where a range typed as {@code text} starts. */
    public static Optional<Instant> start(String text, ZoneId zone, Instant now) {
        return read(text, zone, now).map(Read::from);
    }

    /** Where a range typed as {@code text} ends, exclusive — the whole unit typed included. */
    public static Optional<Instant> end(String text, ZoneId zone, Instant now) {
        return read(text, zone, now).map(Read::to);
    }

    /** A whole day: "today", "yesterday" or a date. A time of day is not a day. */
    public static Optional<Span> day(String text, ZoneId zone, Instant now) {
        return dateOf(clean(text), zone, now).map(date -> wholeDay(date, zone));
    }

    /** The last 2h, 30m, 3d… up to now. */
    public static Optional<Span> last(String text, Instant now) {
        return Times.parse(text).filter(length -> !length.isZero() && !length.isNegative())
                .map(length -> new Span(now.minus(length), now));
    }

    /** A moment written so that {@link #start} reads it back exactly — no spaces, the offset included. */
    public static String write(Instant moment, ZoneId zone) {
        return WRITTEN.format(moment.truncatedTo(ChronoUnit.SECONDS).atZone(zone));
    }

    /** A moment for a person to read, to the minute. */
    public static String show(Instant moment, ZoneId zone) {
        return SHOWN.format(moment.atZone(zone));
    }

    private record Read(Instant from, Instant to) {
    }

    private static Optional<Read> read(String text, ZoneId zone, Instant now) {
        String typed = clean(text);
        if (typed.isEmpty()) {
            return Optional.empty();
        }
        if (typed.equals("now")) {
            return Optional.of(new Read(now, now));
        }
        try {
            Instant exact = OffsetDateTime.parse(typed.toUpperCase(Locale.ROOT), WRITTEN).toInstant();
            return Optional.of(new Read(exact, exact.plusSeconds(1)));
        } catch (DateTimeException notWrittenOut) {
            // A moment as a person types it, below.
        }
        Optional<LocalDate> date = dateOf(typed, zone, now);
        if (date.isPresent()) {
            Span day = wholeDay(date.get(), zone);
            return Optional.of(new Read(day.from(), day.to()));
        }
        Matcher time = TIME.matcher(typed);
        if (time.matches()) {
            return at(LocalDate.ofInstant(now, zone), time, zone);
        }
        Matcher separator = SEPARATOR.matcher(typed);
        if (separator.find()) {
            Optional<LocalDate> day = dateOf(typed.substring(0, separator.start()), zone, now);
            Matcher clock = TIME.matcher(typed.substring(separator.end()));
            if (day.isPresent() && clock.matches()) {
                return at(day.get(), clock, zone);
            }
        }
        return Optional.empty();
    }

    private static Optional<Read> at(LocalDate date, Matcher time, ZoneId zone) {
        try {
            boolean seconds = time.group(3) != null;
            LocalTime clock = LocalTime.of(Integer.parseInt(time.group(1)), Integer.parseInt(time.group(2)),
                    seconds ? Integer.parseInt(time.group(3)) : 0);
            Instant from = ZonedDateTime.of(LocalDateTime.of(date, clock), zone).toInstant();
            return Optional.of(new Read(from, from.plus(seconds ? Duration.ofSeconds(1) : Duration.ofMinutes(1))));
        } catch (DateTimeException impossible) {
            return Optional.empty();
        }
    }

    private static Optional<LocalDate> dateOf(String typed, ZoneId zone, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        switch (typed) {
            case "today":
                return Optional.of(today);
            case "yesterday":
                return Optional.of(today.minusDays(1));
            default:
                break;
        }
        try {
            Matcher iso = ISO_DATE.matcher(typed);
            if (iso.matches()) {
                return Optional.of(LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)),
                        Integer.parseInt(iso.group(3))));
            }
            Matcher german = GERMAN_DATE.matcher(typed);
            if (german.matches()) {
                int year = german.group(3) == null ? today.getYear() : Integer.parseInt(german.group(3));
                return Optional.of(LocalDate.of(year, Integer.parseInt(german.group(2)),
                        Integer.parseInt(german.group(1))));
            }
        } catch (DateTimeException impossible) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Span wholeDay(LocalDate date, ZoneId zone) {
        return new Span(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant());
    }

    private static String clean(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
    }
}
