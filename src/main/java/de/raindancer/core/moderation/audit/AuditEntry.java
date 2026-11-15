package de.raindancer.core.moderation.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One thing somebody did.
 *
 * <h2>Why the who and the what are columns rather than a sentence</h2>
 * The obvious way to write an audit log is a line of prose: "Steve edited Alex's inventory". It reads
 * well and it cannot be searched. The questions this exists to answer are "what has this moderator
 * done" and "what has been done to this player", and neither can be asked of a sentence without
 * guessing at its grammar.
 *
 * <p>So the actor, the subject and the feature are fields, and the sentence is generated from them
 * when somebody wants to read one.
 *
 * <h2>Why the names are kept as well as the UUIDs</h2>
 * Because a UUID is the only thing that stays the same when somebody changes their name, and a name
 * is the only thing anybody reading a log a year later will recognise. Keeping just the UUID means a
 * log nobody can read; keeping just the name means a log that quietly attributes one person's
 * actions to whoever holds that name now. Both, and the name is what it was <em>at the time</em>.
 *
 * @param id          what the journal called it; 0 for an entry not written yet
 * @param at          when it happened
 * @param feature     which part of the server — {@code "invsee"}, {@code "punishment"}, {@code "vanish"}
 * @param action      what was done, in that feature's own words — {@code "opened"}, {@code "banned"}
 * @param actor       who did it; null for something the server did by itself
 * @param actorName   what they were called at the time
 * @param subject     who it was done to, when it was done to somebody
 * @param subjectName what they were called at the time
 * @param detail      one line for a human to read
 * @param world       where, when that means anything
 * @param fields      whatever else this action needs remembered, searchable rather than prose
 */
public record AuditEntry(long id, Instant at, String feature, String action,
                         UUID actor, String actorName, UUID subject, String subjectName,
                         String detail, String world, Map<String, String> fields) {

    public AuditEntry {
        feature = feature == null || feature.isBlank() ? "unknown" : feature.trim();
        action = action == null || action.isBlank() ? "did something" : action.trim();
        at = at == null ? Instant.EPOCH : at;
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    /** A new entry, not yet written. */
    public static Builder of(String feature, String action) {
        return new Builder(feature, action);
    }

    public Optional<UUID> actorId() {
        return Optional.ofNullable(actor);
    }

    public Optional<UUID> subjectId() {
        return Optional.ofNullable(subject);
    }

    public Optional<String> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    /** Who did it, by whichever name there is — for reading, never for matching. */
    public String actorDescription() {
        if (actorName != null && !actorName.isBlank()) {
            return actorName;
        }
        return actor == null ? "the server" : actor.toString();
    }

    public String subjectDescription() {
        if (subjectName != null && !subjectName.isBlank()) {
            return subjectName;
        }
        return subject == null ? "" : subject.toString();
    }

    /** The sentence, built from the fields rather than stored as one. */
    public String saying() {
        StringBuilder said = new StringBuilder(actorDescription()).append(' ').append(action);
        String to = subjectDescription();
        if (!to.isEmpty()) {
            said.append(' ').append(to);
        }
        if (detail != null && !detail.isBlank()) {
            said.append(": ").append(detail);
        }
        return said.toString();
    }

    /** Puts one together without ten arguments in an order nobody remembers. */
    public static final class Builder {

        private final String feature;
        private final String action;
        private UUID actor;
        private String actorName;
        private UUID subject;
        private String subjectName;
        private String detail;
        private String world;
        private final Map<String, String> fields = new LinkedHashMap<>();

        private Builder(String feature, String action) {
            this.feature = feature;
            this.action = action;
        }

        public Builder by(UUID who, String name) {
            this.actor = who;
            this.actorName = name;
            return this;
        }

        public Builder to(UUID who, String name) {
            this.subject = who;
            this.subjectName = name;
            return this;
        }

        public Builder saying(String detail) {
            this.detail = detail;
            return this;
        }

        public Builder in(String world) {
            this.world = world;
            return this;
        }

        /** One more searchable fact. A null value is kept, because "not set" is itself a fact. */
        public Builder with(String name, String value) {
            if (name != null && !name.isBlank()) {
                fields.put(name, value);
            }
            return this;
        }

        public Builder with(String name, int value) {
            return with(name, Integer.toString(value));
        }

        public AuditEntry at(Instant when) {
            return new AuditEntry(0L, when, feature, action, actor, actorName, subject, subjectName,
                    detail, world, fields);
        }
    }
}
