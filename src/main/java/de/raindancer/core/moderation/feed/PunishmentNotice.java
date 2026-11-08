package de.raindancer.core.moderation.feed;

import de.raindancer.core.moderation.punishment.Punishment;

/** A punishment given here, or (lifted) ended early here. */
public record PunishmentNotice(Punishment punishment, boolean lifted) {
}
