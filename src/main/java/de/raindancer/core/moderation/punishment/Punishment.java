package de.raindancer.core.moderation.punishment;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One thing a moderator did, and whether it still applies.
 *
 * <h2>Why lifting adds rather than deletes</h2>
 * A lifted ban is not a ban that never happened. Keeping the record — who gave it, why, who lifted
 * it and why — is the difference between a moderation system and a set of flags, and it is what
 * makes the second offence answerable: "you were banned for this in March" is only available if
 * March is still written down.
 *
 * @param endsAt   when it stops applying; null for a punishment that never does
 * @param liftedAt when a moderator ended it early, if one did
 */
public record Punishment(String id, UUID target, PunishmentKind kind, UUID moderator, String reason,
                         Instant givenAt, Instant endsAt, UUID lifter, String lifterReason,
                         Instant liftedAt) {

    public Punishment {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        reason = reason == null || reason.isBlank() ? "no reason given" : reason.trim();
    }

    static Punishment given(UUID target, PunishmentKind kind, UUID moderator, String reason,
                            Instant now, Duration length) {
        return new Punishment(null, target, kind, moderator, reason, now,
                length == null ? null : now.plus(length), null, null, null);
    }

    /** Whether this still applies at the given moment. */
    public boolean isActiveAt(Instant now) {
        if (!kind.isLasting() || liftedAt != null) {
            return false;
        }
        return endsAt == null || now.isBefore(endsAt);
    }

    /**
     * When it stopped applying: its lifting, its end, or — a warning, a kick, a fine — the moment it was given.
     * Empty while it still applies.
     */
    public Optional<Instant> endedAt(Instant now) {
        if (isActiveAt(now)) {
            return Optional.empty();
        }
        if (liftedAt != null) {
            return Optional.of(liftedAt);
        }
        return Optional.of(kind.isLasting() && endsAt != null ? endsAt : givenAt);
    }

    /** Whether it was meant to last until somebody says otherwise. */
    public boolean isPermanent() {
        return endsAt == null && kind.isLasting();
    }

    /** How much longer it has, or empty when it is permanent or already over. */
    public Optional<Duration> remainingAt(Instant now) {
        if (endsAt == null || !isActiveAt(now)) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, endsAt));
    }

    /**
     * Who ended it early, if anybody.
     *
     * <p>Named differently from the record component it reads — {@code lifter} — because a record's
     * own accessor cannot change its return type, and an {@link Optional} here is worth more than
     * the symmetry: this is a field that is usually absent, and the caller should be made to say
     * what happens then.
     */
    public Optional<UUID> liftedBy() {
        return Optional.ofNullable(lifter);
    }

    public Optional<String> liftReason() {
        return Optional.ofNullable(lifterReason);
    }

    /** The same punishment, ended early. */
    Punishment lifted(UUID by, String why, Instant now) {
        return new Punishment(id, target, kind, moderator, reason, givenAt, endsAt, by,
                why == null || why.isBlank() ? "no reason given" : why.trim(), now);
    }

    /** How long this was for, in the words somebody would use. */
    public String length() {
        return endsAt == null ? "for ever"
                : Durations.describe(Duration.between(givenAt, endsAt));
    }
}
