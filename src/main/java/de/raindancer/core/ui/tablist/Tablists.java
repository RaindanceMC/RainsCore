package de.raindancer.core.ui.tablist;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The tablist, as the players see it.
 *
 * <h2>What is here and what is not</h2>
 * Only the sending. Every decision — the grouping, the ordering, what a world is called, what a line
 * says — is {@link TablistModel}, tested without a server. This turns that into packets.
 *
 * <h2>How the ordering actually works</h2>
 * Minecraft does not let a server say "put these players in this order". It sorts the tablist by the
 * scoreboard team each player is in, alphabetically, and that is the only lever there is. So every
 * player is put in a team named by {@link TablistModel#sortKey}, which sorts world-first — and the
 * grouping falls out of the ordering rather than being drawn.
 *
 * <p>Teams are made on the main scoreboard, with names that start with {@code rc-}, and are cleaned
 * up on shutdown. A server using teams for something else keeps working: nothing here touches a team
 * it did not make.
 *
 * <h2>Why it is refreshed on a timer as well as on events</h2>
 * A player's world changes on an event and their ping changes continuously, so an event-only tablist
 * shows a stale latency for ever. The timer is slow — a couple of seconds — because nothing here is
 * urgent and a tablist rebuilt every tick is packets nobody asked for.
 */
public final class Tablists {

    private static final LogChannel log = Log.of("tablist");

    /** Teams we made, so nothing else's are touched. */
    private static final String TEAM_PREFIX = "rc-";

    private final TablistModel model;
    private volatile String serverName;
    private volatile boolean showWorldOnEachLine;
    /** Off leaves the player list exactly as vanilla — nothing here touches it at all. */
    private volatile boolean enabled = true;
    /** Off stops the team-sorting, so the list is in whatever order the server likes. */
    private volatile boolean groupByWorld = true;
    /** What the owner wrote instead of the built-in header, or empty for the built-in one. */
    private volatile String customHeader = "";
    private volatile String customFooter = "";
    /** Who is on the other servers of the network, by server — listed under the footer. */
    private volatile java.util.function.Supplier<java.util.Map<String, java.util.Set<String>>> elsewhere =
            java.util.Map::of;
    /** Extra frames the header and footer cycle through, and how long each lasts. */
    private volatile Animated headerFrames = Animated.of("");
    private volatile Animated footerFrames = Animated.of("");
    /** Counts refreshes, which is what an animation is measured in. */
    private final AtomicLong tick =
            new AtomicLong();

    /** Who is hidden and should not appear. Nobody, until something says otherwise. */
    private Supplier<Set<UUID>> hidden = Set::of;

    public Tablists(TablistModel model, String serverName) {
        this.model = model;
        this.serverName = serverName;
    }

    /**
     * Who to leave off the list.
     *
     * <p>Pointed at {@code Vanish} by {@code RainsCorePlugin}. Behind a supplier rather than taking the
     * class, so this package keeps knowing nothing about moderation — and so a server with no vanish at
     * all still has a tablist.
     */
    public void hiddenPlayers(Supplier<Set<UUID>> hidden) {
        this.hidden = hidden == null ? Set::<UUID>of : hidden;
    }

    /** Where to ask who is on the other servers of the network; their names are listed under the footer. */
    public void elsewhere(java.util.function.Supplier<java.util.Map<String, java.util.Set<String>>> source) {
        this.elsewhere = source == null ? java.util.Map::of : source;
    }

    public void serverName(String name) {
        this.serverName = name;
    }

    /**
     * Whether each line also says which world that player is in.
     *
     * <p>Off by default: the ordering already groups them, and saying it twice is noise. On for a
     * server that would rather have it beside every name.
     */
    public void showWorldOnEachLine(boolean show) {
        this.showWorldOnEachLine = show;
    }

    /**
     * Whether the player list is ours at all.
     *
     * <p>Switching it off puts everything back: the teams are removed and no header, footer or name
     * is sent again. A setting that only stops updating would leave whatever was last drawn on
     * screen for ever, which is worse than not having the setting.
     */
    public void enabled(boolean on) {
        boolean was = this.enabled;
        this.enabled = on;
        if (was && !on) {
            shutdown();
            restoreNames();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Whether players are sorted so everybody in one world is together. */
    public void groupByWorld(boolean group) {
        boolean was = this.groupByWorld;
        this.groupByWorld = group;
        if (was && !group) {
            // Leave nothing behind: a team that stops being updated still sorts.
            shutdown();
        }
    }

    /** What the owner wrote instead of the built-in header. Empty uses the built-in one. */
    public void header(String miniMessage) {
        this.customHeader = miniMessage == null ? "" : miniMessage;
    }

    public void footer(String miniMessage) {
        this.customFooter = miniMessage == null ? "" : miniMessage;
    }

    /**
     * Extra header lines to cycle through — the rules, an event, a vote that is running.
     *
     * <p>The tablist is one of the few places on a server with room to say something, and a header
     * that never changes is one nobody reads twice.
     *
     * @param everyTicks how many refreshes each frame lasts; the list redraws about twice a second,
     *                   so 4 is roughly two seconds and 1 is a strobe
     */
    public void headerFrames(List<String> frames, int everyTicks) {
        this.headerFrames = Animated.of(frames).everyTicks(everyTicks);
    }

    public void footerFrames(List<String> frames, int everyTicks) {
        this.footerFrames = Animated.of(frames).everyTicks(everyTicks);
    }

    public TablistModel model() {
        return model;
    }

    /** Rebuilds everybody's tablist. Called on a timer, and when somebody joins or changes world. */
    public void refresh() {
        if (!enabled) {
            return;
        }
        // Hiding a player's entity is not hiding them from a *custom* tablist: this builds its own
        // list from getOnlinePlayers(), so a vanished moderator was still on it, name, world and ping.
        // The one place anybody looks to see who is about.
        //
        // Two lists, not one, and this is the part that used to be wrong: everybody in `allOnline`
        // gets sent a packet — the vanished included — so being hidden never also means being
        // forgotten. What that packet *says* comes from `visible` instead: the header's count, the
        // footer's per-world tallies, nobody hidden is ever in either, not even for themselves. A
        // version that skipped a vanished player when deciding who to send to left exactly that one
        // player's own tablist without a single further update for as long as they stayed hidden —
        // frozen on whatever was true the moment before, and un-vanishing simply resumed updates from
        // there, which reads as nothing having changed at all.
        Set<UUID> hidden = this.hidden.get();
        List<Player> allOnline = new ArrayList<>(Bukkit.getOnlinePlayers());

        // Everybody, hidden included — their own line in their own tablist, and the scoreboard team
        // sorting below, are unaffected by vanish: what makes them invisible to everybody else is
        // BukkitVanishSink's own hidePlayer(), which strips a hidden player's whole entry from
        // every other viewer's client before either of these ever runs. This list only ever governs
        // what a player is called and where they sort in a list they are already an entry on — their
        // own, always, and anybody else's only once BukkitVanishSink has let them stay.
        List<TablistEntry> allEntries = new ArrayList<>(allOnline.size());
        List<TablistEntry> visibleEntries = new ArrayList<>(allOnline.size());
        for (Player player : allOnline) {
            TablistEntry entry = new TablistEntry(player.getUniqueId(), player.getName(),
                    player.getWorld().getName(), player.getPing());
            allEntries.add(entry);
            if (!hidden.contains(player.getUniqueId())) {
                visibleEntries.add(entry);
            }
        }

        long now = tick.getAndIncrement();
        // A frame, if there are any, otherwise whatever the plain header is. Frames win because a
        // server that set them meant to: leaving the still header showing under an animation would
        // be a setting that quietly does nothing.
        String headerNow = headerFrames.isAnimated() || !headerFrames.frameAt(now).isEmpty()
                ? headerFrames.frameAt(now) : customHeader;
        String footerNow = footerFrames.isAnimated() || !footerFrames.frameAt(now).isEmpty()
                ? footerFrames.frameAt(now) : customFooter;
        String resolvedCustomHeader = headerNow;
        String resolvedCustomFooter = footerNow;

        Component header = resolvedCustomHeader.isBlank()
                ? model.header(visibleEntries, serverName)
                : model.custom(resolvedCustomHeader, visibleEntries, serverName);
        Component footer = resolvedCustomFooter.isBlank()
                ? model.footer(visibleEntries)
                : model.custom(resolvedCustomFooter, visibleEntries, serverName);
        Component away = model.elsewhere(elsewhere.get());
        if (!away.equals(Component.empty())) {
            footer = footer.append(Component.newline()).append(away).append(Component.newline());
        }

        for (Player player : allOnline) {
            try {
                player.sendPlayerListHeaderAndFooter(header, footer);
            } catch (RuntimeException gone) {
                // Logged out between the list and the send. Normal, not exceptional.
                log.debug("Could not send {} their tablist: {}", player.getName(), gone.toString());
            }
        }

        if (groupByWorld) {
            applyOrder(allEntries);
        }

        for (int index = 0; index < allOnline.size(); index++) {
            Player player = allOnline.get(index);
            TablistEntry entry = allEntries.get(index);
            Component line = showWorldOnEachLine ? model.lineWithWorld(entry) : model.line(entry);
            if (hidden.contains(entry.player())) {
                // Only somebody allowed to see them still has their entry at all, so the mark is for staff.
                line = line.append(Component.text(" [vanished]", net.kyori.adventure.text.format.NamedTextColor.GRAY));
            }
            try {
                // Only when it differs from what the player has now: every call is a packet to every
                // player on the server, so setting it unchanged each refresh was N² packets saying
                // nothing. Compared with the live name, not a cache, so a name somebody else set is
                // still put back exactly as before.
                if (!line.equals(player.playerListName())) {
                    player.playerListName(line);
                }
            } catch (RuntimeException gone) {
                log.debug("Could not name {} in the tablist: {}", player.getName(),
                        gone.toString());
            }
        }
    }

    /**
     * Puts each player in the team that sorts them where they belong.
     *
     * <p>Only touched when it has changed: moving a player between teams is a packet to everybody
     * on the server, and doing it every couple of seconds for every player would be a steady trickle
     * of traffic saying nothing.
     */
    private void applyOrder(List<TablistEntry> entries) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (TablistEntry entry : entries) {
            String wanted = TEAM_PREFIX + model.sortKey(entry);
            Player player = Bukkit.getPlayer(entry.player());
            if (player == null) {
                continue;
            }
            try {
                Team current = board.getEntryTeam(player.getName());
                if (current != null && current.getName().equals(wanted)) {
                    continue;
                }
                if (current != null && current.getName().startsWith(TEAM_PREFIX)) {
                    current.removeEntry(player.getName());
                    // Unregistered once nobody is in it. These live on the MAIN scoreboard, which
                    // Minecraft saves to scoreboard.dat — an empty team left behind is not merely
                    // untidy, it is written to disk for ever, and a server whose players change
                    // world often would accumulate thousands.
                    if (current.getEntries().isEmpty()) {
                        current.unregister();
                    }
                } else if (current != null) {
                    // Somebody else's team. Leaving it alone is the only safe thing to do: taking a
                    // player out of another plugin's team to sort a list would break whatever that
                    // team was for.
                    continue;
                }
                Team team = board.getTeam(wanted);
                if (team == null) {
                    team = board.registerNewTeam(wanted);
                }
                de.raindancer.core.ui.identity.VanillaNametags.applyTo(team);
                team.addEntry(player.getName());
            } catch (RuntimeException failure) {
                log.debug("Could not sort {} in the tablist: {}", entry.name(),
                        failure.toString());
            }
        }
    }

    /** Puts every player's name back to plain, for when the custom list is switched off. */
    private void restoreNames() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                player.playerListName(null);
                player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            } catch (RuntimeException gone) {
                log.debug("Could not restore {}'s tablist entry: {}", player.getName(),
                        gone.toString());
            }
        }
    }

    /** Removes the teams this class made. Called from {@code onDisable}. */
    public void shutdown() {
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            for (Team team : new ArrayList<>(board.getTeams())) {
                if (team.getName().startsWith(TEAM_PREFIX)) {
                    team.unregister();
                }
            }
        } catch (RuntimeException failure) {
            log.debug("Could not tidy up the tablist teams: {}", failure.toString());
        }
    }

    /** Forgets a player who has left, so their team does not linger. */
    public void forget(Player player) {
        if (player == null) {
            return;
        }
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            Team team = board.getEntryTeam(player.getName());
            if (team != null && team.getName().startsWith(TEAM_PREFIX)) {
                team.removeEntry(player.getName());
                if (team.getEntries().isEmpty()) {
                    team.unregister();
                }
            }
        } catch (RuntimeException failure) {
            log.debug("Could not take {} out of their tablist team: {}", player.getName(),
                    failure.toString());
        }
    }

    /** Whether two entries are the same player in the same place — for a cheap change check. */
    static boolean sameSpot(TablistEntry one, TablistEntry other) {
        return one != null && other != null
                && Objects.equals(one.player(), other.player())
                && Objects.equals(one.world(), other.world());
    }
}
