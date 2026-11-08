package de.raindancer.core.moderation.feed;

import java.util.UUID;

/** A player report filed in game. */
public record ReportNotice(String id, UUID reporter, String reporterName, UUID target, String targetName, String reason) {
}
