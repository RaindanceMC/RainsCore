package de.raindancer.core.moderation.feed;

import java.util.UUID;

/**
 * Staff went into or came out of admin mode here.
 *
 * @param durationMillis on leaving: how long they were in it since this server last saw them go in; 0 when unknown
 *                       and always 0 on entering
 */
public record AdminModeNotice(UUID player, boolean entered, long durationMillis) {
}
