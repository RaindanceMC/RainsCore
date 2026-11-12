package de.raindancer.core.moderation.feed;

import java.util.UUID;

/**
 * Staff went into or came out of admin mode here.
 *
 * @param durationMillis on leaving: how long they were in it since this server last saw them go in; 0 when unknown
 *                       and always 0 on entering
 * @param recap          on leaving: what they did meanwhile; null on entering, or when nothing was recording
 */
public record AdminModeNotice(UUID player, boolean entered, long durationMillis, AdminSessionRecap recap) {

    /** Without a recap, as before 2.89.0. */
    public AdminModeNotice(UUID player, boolean entered, long durationMillis) {
        this(player, entered, durationMillis, null);
    }
}
