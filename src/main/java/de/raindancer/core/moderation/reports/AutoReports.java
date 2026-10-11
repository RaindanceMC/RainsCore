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
        Sink handler = sink.get();
        if (handler == null || subject == null || kind == null || kind.isBlank() || text == null || text.isBlank()) {
            return Result.NO_SINK;
        }
        try {
            Result result = handler.file(subject, subjectName, kind.trim(), text.trim());
            return result == null ? Result.NO_SINK : result;
        } catch (RuntimeException broken) {
            return Result.NO_SINK;
        }
    }

    public static int forgetFrom(ClassLoader loader) {
        Sink handler = sink.get();
        if (handler != null && PluginCode.isFrom(handler, loader) && sink.compareAndSet(handler, null)) {
            return 1;
        }
        return 0;
    }

    public static void clear() {
        sink.set(null);
    }
}
