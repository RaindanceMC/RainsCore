package de.raindancer.core.moderation.review;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Things a player asked for that staff have to look at (a map art image, …), told to whoever shows them somewhere
 * else (a Discord channel with Approve and Deny), and decisions coming back from there.
 *
 * <p>The module that owns a kind posts every change of state and registers one {@link Decider} for it. Listeners run
 * on the poster's thread and must be quick; a decider may be called from any thread.
 */
public final class StaffReviews {

    private static final LogChannel log = Log.of("moderation");

    public enum State {
        /** Waits for staff. */
        WAITING,
        /** Approved; the work it starts is still running. */
        APPROVED,
        /** Refused, with a reason. */
        REFUSED,
        /** Approved and carried out. */
        DONE,
        /** Approved, but carrying it out failed; it waits again. */
        FAILED
    }

    public enum Outcome { APPROVED, REFUSED, UNKNOWN, ALREADY_DECIDED, NOT_HERE, FAILED }

    /**
     * @param kind        what sort of thing, e.g. {@code mapart}
     * @param title       one line for staff, e.g. "Map art 2×2"
     * @param link        what the player sent, if anything; empty otherwise
     * @param preview     a small picture of it (JPEG or PNG), only on {@link State#WAITING}; empty otherwise
     * @param detail      a note for staff, e.g. why the preview is missing or what was done
     * @param by          who decided; empty while it waits
     */
    public record Review(String kind, String id, UUID player, String name, String title, String link, byte[] preview,
                         String previewType, String detail, State state, String by, String reason) {

        public Review {
            kind = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
            link = link == null ? "" : link;
            preview = preview == null ? new byte[0] : preview;
            previewType = previewType == null ? "" : previewType;
            detail = detail == null ? "" : detail;
            by = by == null ? "" : by;
            reason = reason == null ? "" : reason;
        }

        public static Review waiting(String kind, String id, UUID player, String name, String title, String link,
                                     byte[] preview, String previewType) {
            return new Review(kind, id, player, name, title, link, preview, previewType, "", State.WAITING, "", "");
        }

        public Review withDetail(String note) {
            return new Review(kind, id, player, name, title, link, preview, previewType, note, state, by, reason);
        }

        public Review approved(String who) {
            return next(State.APPROVED, who, "", detail);
        }

        public Review refused(String who, String why) {
            return next(State.REFUSED, who, why, detail);
        }

        public Review done(String who, String note) {
            return next(State.DONE, who, "", note);
        }

        public Review failed(String who, String why) {
            return next(State.FAILED, who, why, detail);
        }

        private Review next(State to, String who, String why, String note) {
            return new Review(kind, id, player, name, title, link, new byte[0], "", note, to, who, why);
        }
    }

    /** Carries out a decision made outside the game. {@code reason} may be empty. Must not throw. */
    @FunctionalInterface
    public interface Decider {
        Outcome decide(String id, boolean approve, String by, String reason);
    }

    /**
     * A request made outside the game (e.g. a picture uploaded on Discord) for a player's own account.
     *
     * @param options what the kind needs besides the link, e.g. a map art size "2x2"
     * @param via     where it came from, e.g. {@code discord}
     */
    public record Submission(String kind, UUID player, String name, String link, String options, String via) {
    }

    /**
     * @param outcome lower case, e.g. {@code asked}, {@code not_prime}; {@code not_here} when nothing takes the kind
     * @param ref     the new request's id when it was taken, else empty
     * @param text    one plain sentence for the person who sent it
     */
    public record Answer(String outcome, String ref, String text) {

        public Answer {
            outcome = outcome == null ? "failed" : outcome;
            ref = ref == null ? "" : ref;
            text = text == null ? "" : text;
        }
    }

    /** Takes a {@link Submission} of its kind. Any thread. */
    @FunctionalInterface
    public interface Intake {
        Answer submit(Submission submission);
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private final List<Consumer<Review>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Decider> deciders = new ConcurrentHashMap<>();
    private final Map<String, Intake> intakes = new ConcurrentHashMap<>();

    public Subscription onReview(Consumer<Review> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void post(Review review) {
        for (Consumer<Review> listener : listeners) {
            try {
                listener.accept(review);
            } catch (RuntimeException failed) {
                log.warn("A staff review listener failed: {}", failed.toString());
            }
        }
    }

    /** One per kind; a second one replaces the first. */
    public Subscription decider(String kind, Decider decider) {
        String key = kind.toLowerCase(Locale.ROOT);
        deciders.put(key, decider);
        return () -> deciders.remove(key, decider);
    }

    public Outcome decide(String kind, String id, boolean approve, String by, String reason) {
        Decider decider = kind == null ? null : deciders.get(kind.toLowerCase(Locale.ROOT));
        if (decider == null) {
            return Outcome.NOT_HERE;
        }
        try {
            Outcome outcome = decider.decide(id, approve, by == null ? "" : by, reason == null ? "" : reason);
            return outcome == null ? Outcome.FAILED : outcome;
        } catch (RuntimeException failed) {
            log.warn("Deciding {} {} failed: {}", kind, id, failed.toString());
            return Outcome.FAILED;
        }
    }

    /** One per kind; a second one replaces the first. Since 2.95.0. */
    public Subscription intake(String kind, Intake intake) {
        String key = kind.toLowerCase(Locale.ROOT);
        intakes.put(key, intake);
        return () -> intakes.remove(key, intake);
    }

    public Answer submit(Submission submission) {
        Intake intake = submission.kind() == null ? null : intakes.get(submission.kind().toLowerCase(Locale.ROOT));
        if (intake == null) {
            return new Answer("not_here", "", "This server does not take that kind of request.");
        }
        try {
            Answer answer = intake.submit(submission);
            return answer == null ? new Answer("failed", "", "Something went wrong.") : answer;
        } catch (RuntimeException failed) {
            log.warn("Taking {} from {} failed: {}", submission.kind(), submission.via(), failed.toString());
            return new Answer("failed", "", "Something went wrong.");
        }
    }
}
