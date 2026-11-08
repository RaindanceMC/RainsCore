package de.raindancer.core.moderation.feed;

import java.util.UUID;

/**
 * One failed anti-cheat check past its alert level.
 *
 * @param level the player's violation level for the check, after this flag
 * @param client the player's client version ("26.3"), null when unknown
 * @param brand the client brand ("fabric"), null when unknown
 */
public record AnticheatAlert(UUID player, String name, String check, double level, String detail, int ping,
                             String client, String brand) {
}
