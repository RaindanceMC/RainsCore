package de.raindancer.core.content.advancement;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Where advancements actually live: the server. A seam, so the bookkeeping is testable without one. */
public interface AdvancementSink {

    /** Loads these (key → JSON) and answers which of them the server accepted. */
    Set<String> publish(Map<String, String> jsonByKey);

    /** The criteria this online player has met. */
    Set<String> met(UUID player, String key);

    /** Meets a criterion; true only if it was not met before. */
    boolean grant(UUID player, String key, String criterion);

    boolean revoke(UUID player, String key, String criterion);
}
