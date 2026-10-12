package de.raindancer.core.platform.command;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/**
 * Who a typed name, nickname or selector meant — online or not — and how it was understood, so a command
 * can answer the right thing: "they are offline" is not "nobody is called that", and neither is "you may
 * not use selectors".
 *
 * @param typed   what was typed
 * @param kind    how it was understood
 * @param matches everybody it meant, online first; an {@link OfflinePlayer} that is online is a {@link Player}
 */
public record PlayerLookup(String typed, Kind kind, List<OfflinePlayer> matches) {

    public enum Kind {
        /** {@code @a}, {@code @p}, {@code @e[...]} — online players only, by the game's own parser. */
        SELECTOR,
        /** A selector the sender may not use, or one that does not parse. */
        SELECTOR_REFUSED,
        /** Somebody's real name. */
        NAME,
        /** Somebody's nickname. */
        NICKNAME,
        /** {@code @TAG}: a group a plugin offers through {@link PlayerGroups} — its members, online or not. */
        GROUP,
        /** Nobody. */
        NONE
    }

    public PlayerLookup {
        matches = List.copyOf(matches);
    }

    public static PlayerLookup none(String typed, Kind kind) {
        return new PlayerLookup(typed, kind, List.of());
    }

    public boolean isEmpty() {
        return matches.isEmpty();
    }

    /** The matches who are online right now. */
    public List<Player> online() {
        return matches.stream().filter(OfflinePlayer::isOnline)
                .map(match -> match instanceof Player player ? player : match.getPlayer())
                .filter(java.util.Objects::nonNull).toList();
    }

    /** Found somebody, but nobody of them is here. */
    public boolean isOfflineOnly() {
        return !matches.isEmpty() && online().isEmpty();
    }

    /** The one match, when there is exactly one. */
    public Optional<OfflinePlayer> single() {
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    /** The one match, when there is exactly one and they are online. */
    public Optional<Player> singleOnline() {
        return single().filter(OfflinePlayer::isOnline)
                .map(match -> match instanceof Player player ? player : match.getPlayer());
    }
}
