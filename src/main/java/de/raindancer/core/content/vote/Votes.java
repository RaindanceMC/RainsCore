package de.raindancer.core.content.vote;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.world.time.Times;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Every vote running on the server.
 *
 * <h2>Why Core owns this</h2>
 * Because the simplest useful version — an operator asks a question, everybody answers, the answer
 * with the most votes wins — is wanted by half the plugins on a server and is wrong in the same ways
 * every time it is rewritten: somebody votes twice, a changed vote is counted as two, the result is
 * read mid-write, or a tie quietly declares a winner. Each of those is a public argument rather than
 * a bug report.
 *
 * <p>It is also the thing the town council needs. "The council must approve a claim inside a town"
 * is a vote with a named electorate and a deadline, which is this class with a set passed in.
 *
 * <h2>Using it</h2>
 * <pre>{@code
 * Vote vote = core.votes().open(op, "Reset the farm world?", List.of("Yes", "No"),
 *         Times.parse("2min").orElseThrow()).orElseThrow();
 *
 * core.votes().cast(vote.id(), player, "Yes");     // answers with a Ballot saying what happened
 * core.votes().sweep().forEach(this::announce);    // on a timer; each id once, as it ends
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * Safe from any thread.
 */
public final class Votes {

    private static final LogChannel log = Log.of("votes");

    /** How long a finished vote's result is kept before it is let go. */
    private static final Duration KEEP_RESULTS = Duration.ofHours(24);

    private final LongSupplier clock;
    private final Map<UUID, Vote> votes = new ConcurrentHashMap<>();
    /** Which finished votes have already been reported, so a timer does not announce them twice. */
    private final Set<UUID> announced = ConcurrentHashMap.newKeySet();

    /** @param clock milliseconds; injected so deadlines can be tested without waiting for them */
    public Votes(LongSupplier clock) {
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------- starting

    /** A vote everybody may answer. */
    public Optional<Vote> open(UUID startedBy, String question, List<String> options,
                               Duration lasting) {
        return open(startedBy, question, options, lasting, null);
    }

    /**
     * A vote, optionally with a named electorate.
     *
     * <p>Refuses rather than corrects: a question with one answer, two answers spelled the same, or
     * no deadline are all mistakes worth stopping at the point they are made. A vote that never
     * closes in particular is one nobody ever acts on.
     *
     * @param mayVote who is being asked, or null for everybody
     * @return the vote, or empty when it was not a vote
     */
    public Optional<Vote> open(UUID startedBy, String question, List<String> options,
                               Duration lasting, Collection<UUID> mayVote) {
        if (question == null || question.isBlank()) {
            return refuse("a vote needs a question");
        }
        if (options == null || options.size() < 2) {
            return refuse("a vote needs at least two answers; one answer is an announcement");
        }
        if (lasting == null || lasting.isZero() || lasting.isNegative()) {
            return refuse("a vote needs a deadline, or nobody ever acts on it");
        }
        List<String> cleaned = options.stream()
                .filter(option -> option != null && !option.isBlank())
                .map(String::trim)
                .toList();
        if (cleaned.size() != options.size()) {
            return refuse("a vote cannot have a blank answer");
        }
        if (cleaned.stream().map(Vote::key).distinct().count() != cleaned.size()) {
            return refuse("two answers spelled the same split the vote for no reason");
        }

        long now = clock.getAsLong();
        Vote vote = new Vote(UUID.randomUUID(), startedBy, question.trim(), cleaned, now,
                now + lasting.toMillis(), mayVote == null ? null : Set.copyOf(mayVote));
        votes.put(vote.id(), vote);
        log.info("Vote opened: \"{}\" with {} answers, closing in {}", vote.question(),
                cleaned.size(), Times.brief(lasting));
        return Optional.of(vote);
    }

    private Optional<Vote> refuse(String why) {
        log.warn("A vote was not started: {}", why);
        return Optional.empty();
    }

    // ---------------------------------------------------------------------------- answering

    /** Records somebody's answer, and says what happened. */
    public Ballot cast(UUID voteId, UUID player, String option) {
        Vote vote = voteId == null ? null : votes.get(voteId);
        if (vote == null) {
            return Ballot.NO_SUCH_VOTE;
        }
        return vote.record(player, option, clock.getAsLong());
    }

    /** Whether somebody has answered. Not what they answered. */
    public boolean hasVoted(UUID voteId, UUID player) {
        Vote vote = voteId == null ? null : votes.get(voteId);
        return vote != null && vote.hasVoted(player);
    }

    // ---------------------------------------------------------------------------- looking

    public Optional<Vote> byId(UUID voteId) {
        return voteId == null ? Optional.empty() : Optional.ofNullable(votes.get(voteId));
    }

    /** Everything still taking answers. */
    public List<Vote> open() {
        long now = clock.getAsLong();
        return votes.values().stream().filter(vote -> vote.isOpen(now)).toList();
    }

    /** Everything one person is being asked and has not answered yet. */
    public List<Vote> waitingOn(UUID player) {
        long now = clock.getAsLong();
        return votes.values().stream()
                .filter(vote -> vote.isOpen(now))
                .filter(vote -> vote.mayVote(player))
                .filter(vote -> !vote.hasVoted(player))
                .toList();
    }

    /** How a vote stands, or how it ended. */
    public Optional<Tally> tally(UUID voteId) {
        return byId(voteId).map(vote -> vote.tally(clock.getAsLong()));
    }

    // ---------------------------------------------------------------------------- ending

    /**
     * Ends one early.
     *
     * @return whether this changed anything; false when it had already ended
     */
    public boolean close(UUID voteId) {
        Vote vote = voteId == null ? null : votes.get(voteId);
        if (vote == null || !vote.isOpen(clock.getAsLong())) {
            return false;
        }
        vote.close();
        return true;
    }

    /**
     * Finds votes whose time has run out, and forgets results nobody needs any more.
     *
     * <p>Called on a timer. Each finished vote comes back <em>once</em>: a timer that announced the
     * result every second until somebody restarted the server would be worse than no announcement.
     *
     * @return the votes that have just ended, in no particular order
     */
    public List<UUID> sweep() {
        long now = clock.getAsLong();
        List<UUID> justEnded = new ArrayList<>();
        for (Vote vote : List.copyOf(votes.values())) {
            if (!vote.isOpen(now) && announced.add(vote.id())) {
                justEnded.add(vote.id());
                log.info("Vote ended: \"{}\": {}", vote.question(), vote.tally(now).describe());
            }
            // Kept for a day so the result outlives the vote and somebody can still act on it;
            // let go after that, because every vote ever held is a leak with a long fuse.
            if (!vote.isOpen(now) && now - vote.closesAt() > KEEP_RESULTS.toMillis()) {
                votes.remove(vote.id());
                announced.remove(vote.id());
            }
        }
        return justEnded;
    }

    /** How many votes are being remembered at all, running or finished. */
    public int size() {
        return votes.size();
    }
}
