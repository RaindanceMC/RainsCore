package de.raindancer.core.moderation.reports;

import de.raindancer.core.platform.util.PluginCode;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Staff reports filed by a machine: offered by whichever plugin keeps the report queue (moderation), used by
 * whichever detects something on its own (the anti-cheat). Static and process-wide, like
 * {@link de.raindancer.core.moderation.rules.RuleBreaches}: neither knows the other. A report only ever asks
 * staff to look; nothing here punishes.
 */
public final class AutoReports {

    /** What became of a filing. */
    public enum Result {
        /** A new report is in the queue. */
        FILED,
        /** A live report of this kind about this player is already in the queue, so nothing was added. */
        DUPLICATE,
        /** The queue refused it (switched off, rate limit). */
        REFUSED,
        /** Nobody keeps a queue, or it failed. */
        NO_SINK
    }

    @FunctionalInterface
    public interface Sink {

        /**
         * Files a report with no human reporter ("the console"). Must not file a second one while a live one
         * of the same kind exists for the same subject. May be called from any thread.
         *
         * @param kind a short lower-case label such as "movement", the report text starts with it
         * @param text what was seen, in plain words
         */
        Result file(UUID subject, String subjectName, String kind, String text);

        /**
         * As {@link #file(UUID, String, String, String)}, with links to what the detecting plugin keeps about it —
         * {@code "evidence" -> "1234"}, {@code "replay" -> "r-77"} — for staff to open from the report. A queue that
         * keeps no links files the report without them.
         */
        default Result file(UUID subject, String subjectName, String kind, String text, java.util.Map<String, String> links) {
            return file(subject, subjectName, kind, text);
        }
    }

    /** Opens what a link points at for a member of staff — offered by the plugin that keeps it. */
    @FunctionalInterface
    public interface Opener {
        /** @return whether there was something to open */
        boolean open(org.bukkit.entity.Player staff, String id);
    }

    private static final java.util.Map<String, Opener> openers = new java.util.concurrent.ConcurrentHashMap<>();

    /** Who opens links of {@code kind} ("evidence", "replay"). */
    public static void opens(String kind, Opener opener) {
        if (kind != null && opener != null) {
            openers.put(kind, opener);
        }
    }

    /** Whether a link of {@code kind} can be opened on this server. */
    public static boolean canOpen(String kind) {
        return kind != null && openers.containsKey(kind);
    }

    /** Opens a link for a member of staff. @return whether it was opened */
    public static boolean open(org.bukkit.entity.Player staff, String kind, String id) {
        Opener opener = kind == null ? null : openers.get(kind);
        if (opener == null || staff == null || id == null) {
            return false;
        }
        try {
            return opener.open(staff, id);
        } catch (RuntimeException broken) {
            return false;
        }
    }

    private static final AtomicReference<Sink> sink = new AtomicReference<>();

    private AutoReports() {
    }

    public static void provide(Sink handler) {
        sink.set(handler);
    }

    public static void withdraw(Sink handler) {
        sink.compareAndSet(handler, null);
    }

    public static boolean available() {
        return sink.get() != null;
    }

    public static Result file(UUID subject, String subjectName, String kind, String text) {
        return file(subject, subjectName, kind, text, java.util.Map.of());
    }

    /** As {@link #file(UUID, String, String, String)}, with links to the evidence (see {@link Sink#file}). */
    public static Result file(UUID subject, String subjectName, String kind, String text,
                              java.util.Map<String, String> links) {
        Sink handler = sink.get();
        if (handler == null || subject == null || kind == null || kind.isBlank() || text == null || text.isBlank()) {
            return Result.NO_SINK;
        }
        try {
            Result result = handler.file(subject, subjectName, kind.trim(), text.trim(),
                    links == null ? java.util.Map.of() : java.util.Map.copyOf(links));
            return result == null ? Result.NO_SINK : result;
        } catch (RuntimeException broken) {
            return Result.NO_SINK;
        }
    }

    public static int forgetFrom(ClassLoader loader) {
        openers.values().removeIf(opener -> PluginCode.isFrom(opener, loader));
        Sink handler = sink.get();
        if (handler != null && PluginCode.isFrom(handler, loader) && sink.compareAndSet(handler, null)) {
            return 1;
        }
        return 0;
    }

    public static void clear() {
        sink.set(null);
        openers.clear();
    }
}
