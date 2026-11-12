package de.raindancer.core.ui.tablist;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code <until:2026-11-21T16:00+01:00>} in a header or footer: "8d 16h 3m" until that moment, and once it has
 * passed the text after a bar ({@code <until:…|We're open!>}) or nothing. A time without an offset is the
 * server's own time, so write the offset when the server does not run in the zone you mean.
 */
final class Until {

    private static final Pattern TAG = Pattern.compile("<until:([^|>]+)(?:\\|([^>]*))?>");

    private Until() {
    }

    static String fill(String text, long now, ZoneId zone) {
        if (!text.contains("<until:")) {
            return text;
        }
        Matcher tag = TAG.matcher(text);
        StringBuilder filled = new StringBuilder();
        while (tag.find()) {
            Long at = parse(tag.group(1).strip(), zone);
            String shown;
            if (at == null) {
                shown = tag.group();
            } else if (now < at) {
                shown = remaining(at - now);
            } else {
                shown = tag.group(2) == null ? "" : tag.group(2);
            }
            tag.appendReplacement(filled, Matcher.quoteReplacement(shown));
        }
        return tag.appendTail(filled).toString();
    }

    private static Long parse(String raw, ZoneId zone) {
        try {
            return OffsetDateTime.parse(raw).toInstant().toEpochMilli();
        } catch (DateTimeParseException noOffset) {
            try {
                return LocalDateTime.parse(raw).atZone(zone).toInstant().toEpochMilli();
            } catch (DateTimeParseException notATime) {
                return null;
            }
        }
    }

    /** Rounded up to the minute, so it never says 0 while the moment is still ahead. */
    private static String remaining(long millis) {
        long minutes = Math.max(1, (millis + 59_999) / 60_000);
        long days = minutes / 1440, hours = (minutes % 1440) / 60, mins = minutes % 60;
        if (days > 0) {
            return days + "d " + hours + "h " + mins + "m";
        }
        return hours > 0 ? hours + "h " + mins + "m" : mins + "m";
    }
}
