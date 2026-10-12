package de.raindancer.core.platform.command;

import de.raindancer.core.ui.identity.Nicknames;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Who somebody means when they type a name — or {@code @a}, {@code @p}, {@code @r}, {@code @s} and every
 * filter vanilla allows on them — at a command that acts on players.
 *
 * <h2>Why Core's</h2>
 * Every command that moves, heals or hands something to "somebody" asks this, and a copy that only
 * understood names would be the command that silently refuses {@code @a} on exactly the server that
 * expected it to work like vanilla's own {@code /tp}.
 *
 * <h2>Why the server's own parser</h2>
 * {@link Server#selectEntities} is vanilla's selector grammar, filters and permission check included
 * (selectors need the vanilla selector permission). A second grammar here would disagree with
 * {@code /tp} on some filter by next version.
 *
 * <h2>Nicknames</h2>
 * Wherever a name is accepted, so is a nickname from Core's {@link Nicknames} — typed with underscores
 * for spaces — and tab completion offers both. A real name always wins over somebody else's nickname
 * that reads the same, so a nickname can never be used to redirect a command meant for a real player.
 */
public final class PlayerTargets {

    private static final List<String> SELECTORS = List.of("@a", "@p", "@r", "@s");

    /** How many names a suggestion list offers at most; past this the client scrolls a wall. */
    private static final int MAX_SUGGESTIONS = 50;

    /** Set by Core when it enables; null before, after, and in tests that want real names only. */
    private static volatile Nicknames nicknames;
    /** Every name Core has seen, for players Paper's name cache has forgotten. Null: the player files are searched. */
    private static volatile de.raindancer.core.social.presence.KnownNames knownNames;

    private PlayerTargets() {
    }

    /** Where real names Paper has forgotten are found. Null: searched in the server's player files instead. */
    public static void useKnownNames(de.raindancer.core.social.presence.KnownNames names) {
        knownNames = names;
    }

    /**
     * Somebody offline this server has seen, by their real name, in any case — Paper's cache first, then Core's
     * own names, then (without those) the player files. Never a lookup against Mojang.
     */
    public static Optional<OfflinePlayer> byRealName(Server server, String name) {
        if (server == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        Player online = server.getPlayerExact(name.trim());
        return online != null ? Optional.of(online) : Optional.ofNullable(seenOffline(server, name.trim()));
    }

    private static OfflinePlayer seenOffline(Server server, String typed) {
        OfflinePlayer cached = server.getOfflinePlayerIfCached(typed);
        if (cached != null) {
            return cached;
        }
        de.raindancer.core.social.presence.KnownNames names = knownNames;
        if (names != null) {
            return names.idOf(typed).map(server::getOfflinePlayer).orElse(null);
        }
        OfflinePlayer[] everybody = server.getOfflinePlayers();
        if (everybody != null) {
            for (OfflinePlayer who : everybody) {
                if (who != null && typed.equalsIgnoreCase(who.getName())) {
                    return who;
                }
            }
        }
        return null;
    }

    /** Which nickname directory names are also looked up in. Null for real names only. */
    public static void useNicknames(Nicknames directory) {
        nicknames = directory;
    }

    /** The online player {@code text} names, by real name first and nickname second. */
    public static Optional<Player> online(Server server, String text) {
        if (server == null || text == null || text.isBlank()) {
            return Optional.empty();
        }
        String typed = text.trim();
        Player exact = server.getPlayerExact(typed);
        if (exact != null) {
            return Optional.of(exact);
        }
        if (isRealName(server, typed)) {
            // Somebody real is called that and is not here. A nickname must never redirect a command
            // meant for them: /ban Griefer with Griefer offline would otherwise ban whoever took
            // "Griefer" as a nickname.
            return Optional.empty();
        }
        return nicknameOwner(typed).map(server::getPlayer);
    }

    /** Whether {@code text} is the real name of anybody this server has seen, online or not. */
    public static boolean isRealName(Server server, String text) {
        if (server == null || text == null || text.isBlank()) {
            return false;
        }
        String typed = text.trim();
        return server.getPlayerExact(typed) != null || seenOffline(server, typed) != null;
    }

    /**
     * Anybody {@code text} names, online or not: a real name online, a real name the server has seen,
     * then a nickname. Never a lookup against Mojang — a typo must not stall the server thread.
     */
    public static Optional<OfflinePlayer> find(Server server, String text) {
        if (server == null || text == null || text.isBlank()) {
            return Optional.empty();
        }
        String typed = text.trim();
        Player exact = server.getPlayerExact(typed);
        if (exact != null) {
            return Optional.of(exact);
        }
        // A real name, even of somebody offline, before any nickname — see online().
        OfflinePlayer known = seenOffline(server, typed);
        if (known != null) {
            return Optional.of(known);
        }
        Optional<UUID> owner = nicknameOwner(typed);
        if (owner.isPresent()) {
            Player here = server.getPlayer(owner.get());
            return Optional.of(here != null ? here : server.getOfflinePlayer(owner.get()));
        }
        return Optional.empty();
    }

    /** The UUID {@code text} names, the same way as {@link #find}. */
    public static Optional<UUID> idOf(Server server, String text) {
        return find(server, text).map(OfflinePlayer::getUniqueId);
    }

    /** What to call somebody in a message: their nickname when they have one, else their name. */
    public static String shownName(OfflinePlayer who) {
        if (who == null) {
            return "somebody";
        }
        Nicknames directory = nicknames;
        Optional<String> nickname = directory == null ? Optional.empty() : directory.of(who.getUniqueId());
        if (nickname.isPresent()) {
            return nickname.get();
        }
        String name = who.getName();
        return name == null || name.isBlank() ? who.getUniqueId().toString() : name;
    }

    private static Optional<UUID> nicknameOwner(String typed) {
        Nicknames directory = nicknames;
        return directory == null ? Optional.empty() : directory.ownerOf(typed);
    }

    /**
     * The online players {@code text} means, each once.
     *
     * <p>A selector that does not parse, or one the sender may not use, matches nobody rather than
     * throwing — the caller says "that matched nobody", which is true, and the command does not end in a
     * stack trace.
     */
    public static List<Player> resolve(Server server, CommandSender sender, String text) {
        if (server == null || text == null || text.isBlank()) {
            return List.of();
        }
        String typed = text.trim();
        if (!typed.startsWith("@")) {
            return online(server, typed).map(List::of).orElse(List.of());
        }
        if (PlayerGroups.isGroup(typed)) {
            List<UUID> members = PlayerGroups.members(typed);
            if (!mayName(sender, members)) {
                return List.of();
            }
            return members.stream().map(server::getPlayer).filter(java.util.Objects::nonNull).toList();
        }
        List<Entity> matched;
        try {
            matched = server.selectEntities(sender, typed);
        } catch (IllegalArgumentException malformed) {
            return List.of();
        }
        if (matched == null) {
            return List.of();
        }
        Set<Player> players = new LinkedHashSet<>();
        for (Entity entity : matched) {
            if (entity instanceof Player player) {
                players.add(player);
            }
        }
        return List.copyOf(players);
    }

    /**
     * Whether {@code sender} may name a group: the console and anybody with selector rights may name any; a
     * player only one they are in — or {@code @TAG} would let anybody mass-target, and count, another guild.
     */
    private static boolean mayName(CommandSender sender, List<UUID> members) {
        if (sender == null || !(sender instanceof org.bukkit.entity.Player player)) {
            return true;
        }
        return player.hasPermission(SELECTOR_NODE) || members.contains(player.getUniqueId());
    }

    /** The vanilla node that lets somebody use {@code @a} and the rest in a command at all. */
    public static final String SELECTOR_NODE = "minecraft.command.selector";

    /**
     * Who {@code text} means: a selector, a real name, or a nickname — online <em>and</em> offline, with how
     * it was understood. The one call a command should make; the others are shorthands of it.
     *
     * @param sender who typed it — a selector is parsed as them and needs {@link #SELECTOR_NODE}; null is the
     *               server itself, which may use any
     */
    public static PlayerLookup lookup(Server server, CommandSender sender, String text) {
        if (server == null || text == null || text.isBlank()) {
            return PlayerLookup.none(text == null ? "" : text, PlayerLookup.Kind.NONE);
        }
        String typed = text.trim();
        if (PlayerGroups.isGroup(typed)) {
            List<UUID> ids = PlayerGroups.members(typed);
            if (!mayName(sender, ids)) {
                return PlayerLookup.none(typed, PlayerLookup.Kind.SELECTOR_REFUSED);
            }
            List<OfflinePlayer> members = new ArrayList<>();
            for (UUID member : ids) {
                Player here = server.getPlayer(member);
                members.add(here != null ? here : server.getOfflinePlayer(member));
            }
            return new PlayerLookup(typed, PlayerLookup.Kind.GROUP, members);
        }
        if (isSelector(typed)) {
            if (sender != null && !sender.hasPermission(SELECTOR_NODE)) {
                return PlayerLookup.none(typed, PlayerLookup.Kind.SELECTOR_REFUSED);
            }
            List<Entity> matched;
            try {
                matched = server.selectEntities(sender == null ? server.getConsoleSender() : sender, typed);
            } catch (IllegalArgumentException malformed) {
                return PlayerLookup.none(typed, PlayerLookup.Kind.SELECTOR_REFUSED);
            }
            Set<OfflinePlayer> players = new LinkedHashSet<>();
            if (matched != null) {
                for (Entity entity : matched) {
                    if (entity instanceof Player player) {
                        players.add(player);
                    }
                }
            }
            return new PlayerLookup(typed, PlayerLookup.Kind.SELECTOR, new ArrayList<>(players));
        }
        Player exact = server.getPlayerExact(typed);
        if (exact != null) {
            return new PlayerLookup(typed, PlayerLookup.Kind.NAME, List.of(exact));
        }
        OfflinePlayer known = seenOffline(server, typed);
        if (known != null) {
            return new PlayerLookup(typed, PlayerLookup.Kind.NAME, List.of(known));
        }
        Optional<UUID> owner = nicknameOwner(typed);
        if (owner.isPresent()) {
            Player here = server.getPlayer(owner.get());
            return new PlayerLookup(typed, PlayerLookup.Kind.NICKNAME,
                    List.of(here != null ? here : server.getOfflinePlayer(owner.get())));
        }
        return PlayerLookup.none(typed, PlayerLookup.Kind.NONE);
    }

    /** The one online player {@code text} means — a selector matching exactly one counts. */
    public static Optional<Player> online(Server server, CommandSender sender, String text) {
        return lookup(server, sender, text).singleOnline();
    }

    /** The one player {@code text} means, online or not — a selector matching exactly one counts. */
    public static Optional<OfflinePlayer> find(Server server, CommandSender sender, String text) {
        return lookup(server, sender, text).single();
    }

    /** Selectors, then every name and nickname — online first, then offline — beginning with what was typed. */
    public static List<String> suggest(Server server, String typed) {
        return suggest(server, null, typed, who -> true);
    }

    /** The same, with only the online players {@code visible} lets through offered as online. */
    public static List<String> suggest(Server server, String typed, Predicate<Player> visible) {
        return suggest(server, null, typed, visible);
    }

    /**
     * Everything that can be typed where a player is wanted: the selectors (only to a sender who may use
     * them), every visible online name and their nicknames, then everybody offline by name and nickname.
     * Offline is always offered: a command can then say "they are offline" rather than the player having
     * to remember how a name they cannot see was spelled.
     */
    public static List<String> suggest(Server server, CommandSender sender, String typed, Predicate<Player> visible) {
        String start = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        Set<String> names = new LinkedHashSet<>();
        if (sender == null || sender.hasPermission(SELECTOR_NODE)) {
            names.addAll(SELECTORS);
        }
        names.addAll(PlayerGroups.suggest(typed == null || typed.isEmpty() ? "@" : typed));
        Set<UUID> shown = new java.util.HashSet<>();
        if (server != null) {
            for (Player player : server.getOnlinePlayers()) {
                if (visible == null || visible.test(player)) {
                    names.add(player.getName());
                    shown.add(player.getUniqueId());
                }
            }
        }
        Nicknames directory = nicknames;
        if (directory != null) {
            names.addAll(directory.suggest(start, shown::contains));
        }
        List<String> online = names.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(start)).toList();
        Set<String> all = new LinkedHashSet<>(online);
        all.addAll(suggestKnown(server, typed, who -> false));
        return cap(new ArrayList<>(all));
    }

    /**
     * Names and nicknames of everybody the server knows — online first — without selectors.
     *
     * @param visibleOnline which online players may be offered as online; the rest are still offered
     *                      as the offline players everybody else would see them as
     */
    public static List<String> suggestKnown(Server server, String typed, Predicate<Player> visibleOnline) {
        String start = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        Set<String> names = new LinkedHashSet<>();
        if (server != null) {
            for (Player player : server.getOnlinePlayers()) {
                if ((visibleOnline == null || visibleOnline.test(player))
                        && player.getName().toLowerCase(Locale.ROOT).startsWith(start)) {
                    names.add(player.getName());
                }
            }
        }
        Nicknames directory = nicknames;
        if (directory != null) {
            names.addAll(directory.suggest(start, who -> true));
        }
        OfflinePlayer[] everybody = server == null ? null : server.getOfflinePlayers();
        if (everybody != null) {
            for (OfflinePlayer who : everybody) {
                if (names.size() >= MAX_SUGGESTIONS) {
                    break;
                }
                String name = who.getName();
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith(start)) {
                    names.add(name);
                }
            }
        }
        return cap(new ArrayList<>(names));
    }

    private static List<String> cap(List<String> names) {
        return names.size() > MAX_SUGGESTIONS ? List.copyOf(names.subList(0, MAX_SUGGESTIONS)) : names;
    }

    /** Whether {@code text} is a selector rather than a name. */
    public static boolean isSelector(String text) {
        return text != null && text.trim().startsWith("@");
    }
}
