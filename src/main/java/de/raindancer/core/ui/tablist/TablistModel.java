package de.raindancer.core.ui.tablist;

import de.raindancer.core.ui.text.Text;
import de.raindancer.core.RainsCore;
import de.raindancer.core.ui.banner.BlockLetters;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.identity.Symbols;
import de.raindancer.core.ui.messages.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * What the tablist says: who is on, and which world they are in.
 *
 * <h2>Why this is separate from the thing that sends it</h2>
 * The same reason as everywhere else here — sending needs a server, deciding does not. Grouping,
 * ordering, what a world is called, what a player's line says and what the header counts are all
 * decisions somebody will want to change, and none of them should need a server to get right.
 *
 * <h2>Why the sort key is a scoreboard team name</h2>
 * The order of the tablist is not ours to set directly. Minecraft sorts it by the scoreboard team a
 * player is in, alphabetically, and that is the only lever there is. So {@link #sortKey} produces a
 * name that sorts the way the groups do — world first, then player — and {@code Tablists} puts each
 * player in a team called that. It is why the key has a length limit: the server refuses a team name
 * past it, and a refused team is a player who lands in the wrong place with no error.
 */
public final class TablistModel {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * How long a sort key may be.
     *
     * <p>A scoreboard team name is capped, and a name past the cap is refused outright — which shows
     * up as one player sorted wrongly and nothing in the log.
     */
    public static final int MAX_SORT_KEY = 16;

    /** The vanilla worlds, in the order they belong in. Anything else follows, alphabetically. */
    private static final List<String> VANILLA_ORDER = List.of("", "_nether", "_the_end");

    private final Identities identities;

    public TablistModel(Identities identities) {
        this.identities = identities;
    }

    // ------------------------------------------------------------------------ worlds

    /**
     * A world's name, as a person would say it.
     *
     * <p>{@code world_nether} is a folder name; "Nether" is what somebody looking at a tablist wants
     * to read. A world nobody has an opinion about gets its own name tidied rather than left raw,
     * which is right often enough to be worth doing and never worse than showing the folder.
     */
    public static String worldLabel(String world) {
        if (world == null || world.isBlank()) {
            return "Somewhere";
        }
        String name = world.trim();
        // The default names first, because they are the ones every server has.
        if (name.equals("world")) {
            return "Overworld";
        }
        if (name.equals("world_nether")) {
            return "Nether";
        }
        if (name.equals("world_the_end")) {
            return "The End";
        }
        // A custom set — farmworld, farmworld_nether, farmworld_the_end — reads as its own place
        // followed by which half of it, which is what somebody with a farm world actually wants.
        if (name.endsWith("_nether")) {
            return readable(name.substring(0, name.length() - "_nether".length())) + " Nether";
        }
        if (name.endsWith("_the_end")) {
            return readable(name.substring(0, name.length() - "_the_end".length())) + " End";
        }
        return readable(name);
    }

    /** The character in front of a world's heading, so the list scans without being read. */
    public static String worldSymbol(String world) {
        String name = world == null ? "" : world.trim();
        if (name.endsWith("_nether")) {
            return Symbols.WARNING;
        }
        if (name.endsWith("_the_end")) {
            return Symbols.STAR;
        }
        return Symbols.HOME;
    }

    private static String readable(String raw) {
        String words = raw.replace('_', ' ').replace('-', ' ').trim();
        return words.isEmpty() ? words
                : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    // ------------------------------------------------------------------------ grouping

    /**
     * Everybody on, gathered under the world they are in.
     *
     * <p>Overworld, Nether, End, then everything else alphabetically — the order somebody expects
     * rather than the order the server happens to list its worlds in. Empty worlds do not appear,
     * because a heading with nobody under it is a heading somebody has to read past.
     */
    public List<TablistGroup> groups(List<TablistEntry> online) {
        if (online == null || online.isEmpty()) {
            return List.of();
        }
        Map<String, List<TablistEntry>> byWorld = new LinkedHashMap<>();
        for (TablistEntry entry : online) {
            byWorld.computeIfAbsent(entry.world(), key -> new ArrayList<>()).add(entry);
        }

        List<String> worlds = new ArrayList<>(byWorld.keySet());
        worlds.sort(Comparator.comparingInt(TablistModel::worldRank)
                .thenComparing(TablistModel::worldLabel, String.CASE_INSENSITIVE_ORDER));

        List<TablistGroup> groups = new ArrayList<>(worlds.size());
        for (String world : worlds) {
            List<TablistEntry> entries = new ArrayList<>(byWorld.get(world));
            // Rank first, heaviest at the top, then by name so two people of the same rank keep a
            // stable order — a list that reshuffles on every refresh is unusable.
            entries.sort(Comparator.<TablistEntry>comparingInt(this::rankOf).reversed()
                    .thenComparing(TablistEntry::name, String.CASE_INSENSITIVE_ORDER));
            groups.add(new TablistGroup(world, worldLabel(world), worldSymbol(world),
                    List.copyOf(entries)));
        }
        return List.copyOf(groups);
    }

    /** Lower sorts first: the three vanilla worlds, then everything else. */
    private static int worldRank(String world) {
        String name = world == null ? "" : world;
        if (name.equals("world")) {
            return 0;
        }
        if (name.equals("world_nether")) {
            return 1;
        }
        if (name.equals("world_the_end")) {
            return 2;
        }
        return 3;
    }

    // ------------------------------------------------------------------------ lines

    /**
     * One player's line: their nametag prefix (an "away" badge, in practice), their chat prefix,
     * their name in their colour, their suffix.
     *
     * <p>Straight through {@link Identities#tablistName}, so a rank set once shows in chat, above
     * their head and here — which is the point of having identities at all.
     */
    public Component line(TablistEntry entry) {
        Component named = identities.tablistName(entry.player(), entry.name());
        return showPing ? named.append(ping(entry)) : named;
    }

    /** The same, with the world after it — for a server that would rather not group. */
    public Component lineWithWorld(TablistEntry entry) {
        Component named = identities.tablistName(entry.player(), entry.name())
                .append(MINI.deserialize("<dark_gray> · <gray>" + worldLabel(entry.world())));
        return showPing ? named.append(ping(entry)) : named;
    }

    /**
     * Whether the latency is written on the line as a number.
     *
     * <p>The five-bar icon at the right-hand end is drawn by the client out of the latency the
     * server sends it, and no packet removes it — so this adds the number rather than replacing the
     * bars. That is the half worth having anyway: "is it me or the server" is not a question five
     * bars can answer, and 30ms and 130ms look identical in them.
     */
    public void showPing(boolean show) {
        this.showPing = show;
    }

    public boolean isShowingPing() {
        return showPing;
    }

    /**
     * The latency, in milliseconds, coloured by how bad it is.
     *
     * <p>A latency the server does not have yet comes through as negative — a player who has just
     * joined, before the first keep-alive has come back — and is left off rather than shown as
     * {@code 0ms}, which is a number that is simply wrong.
     */
    private Component ping(TablistEntry entry) {
        if (entry.ping() < 0) {
            return Component.empty();
        }
        return MINI.deserialize("<dark_gray> " + PING_SEPARATOR + " <" + pingColour(entry.ping())
                + ">" + entry.ping() + "ms");
    }

    /** Green, yellow, red — so a list of forty players can be read without doing arithmetic. */
    public static String pingColour(int milliseconds) {
        if (milliseconds < 100) {
            return "green";
        }
        if (milliseconds < 250) {
            return "yellow";
        }
        return "red";
    }

    private volatile boolean showPing;

    /** What separates a name from its latency. */
    private static final String PING_SEPARATOR = "·";

    /** A group's heading. */
    public Component heading(TablistGroup group) {
        return MINI.deserialize("<dark_gray>" + group.symbol() + " <gray><b>" + escape(group.label())
                + "</b> <dark_gray>(" + group.size() + ")");
    }

    // ------------------------------------------------------------------------ header and footer

    /** What sits above the list: the server, and how many are on. */
    public Component header(List<TablistEntry> online, String serverName) {
        int count = online == null ? 0 : online.size();

        StringBuilder built = new StringBuilder("\n");
        // The logo first: one underneath the name is a footer, not a logo.
        for (String line : logo) {
            built.append(line).append('\n');
        }
        if (title.isEmpty()) {
            String name = serverName == null || serverName.isBlank()
                    ? "This server" : serverName.trim();
            built.append("<gradient:#C9A0FF:#7C5CBF><b>").append(escape(name))
                    .append("</b></gradient>");
        } else {
            // Not escaped: this is the owner's own markup, and the whole point of a title is that
            // they get to colour it. A typo in it is caught below rather than thrown at a player.
            built.append(title);
        }
        // Kept whatever else is above it: how many people are on is the one thing anybody looks at
        // a tablist header for.
        built.append("\n<gray>").append(count)
                .append(count == 1 ? " player online" : " players online").append('\n');

        String markup = built.toString();
        try {
            return MINI.deserialize(markup);
        } catch (RuntimeException badMarkup) {
            // An owner's typo must not empty the tablist for everybody. The words without their
            // colours beat a blank header.
            return Component.text(markup.replaceAll("<[^>]*>", ""));
        }
    }

    /**
     * The name at the top, in the owner's own markup.
     *
     * <p>The header is the largest piece of text a server ever puts in front of a player, and by
     * default it is the MOTD in a gradient — which is fine and says nothing. Empty keeps that.
     */
    public void title(String miniMessage) {
        this.title = miniMessage == null ? "" : miniMessage.trim();
    }

    public String title() {
        return title;
    }

    /**
     * Lines of glyphs above the title.
     *
     * <p>Block characters, or glyphs from a resource pack the server contributes through
     * {@code resourcePacks()} — which is what makes a real logo possible rather than approximated
     * artwork. Empty is no logo.
     */
    public void logo(List<String> lines) {
        this.logo = lines == null ? List.of()
                : lines.stream().filter(line -> line != null && !line.isBlank()).toList();
    }

    public List<String> logo() {
        return logo;
    }

    /**
     * A logo drawn out of a name, for an owner who wants one and cannot draw one.
     *
     * <p>The same block letters the startup banner uses, so a server's console and its tablist look
     * like the same server. Long names become their initials, because nine letters of block text is
     * wider than a tablist column.
     */
    public static List<String> logoFor(String name) {
        return BlockLetters.render(
                BlockLetters.abbreviate(
                        name == null || name.isBlank() ? "MC" : name)).stream()
                .map(line -> "<gradient:#C9A0FF:#7C5CBF>" + line + "</gradient>")
                .toList();
    }

    private volatile String title = "";
    private volatile List<String> logo = List.of();

    /**
     * What sits below it: how many are in each world.
     *
     * <p>The per-world count is the whole point — it answers "is anybody in the nether" without
     * scrolling, which a grouped list only answers by being read.
     */
    public Component footer(List<TablistEntry> online) {
        List<TablistGroup> groups = groups(online);
        if (groups.isEmpty()) {
            return MINI.deserialize("\n" + nobodyOn() + "\n");
        }
        StringBuilder built = new StringBuilder("\n");
        for (int index = 0; index < groups.size(); index++) {
            TablistGroup group = groups.get(index);
            if (index > 0) {
                built.append("<dark_gray>  ·  ");
            }
            built.append("<gray>").append(group.symbol()).append(' ')
                    .append(escape(group.label()))
                    .append(" <white>").append(group.size());
        }
        return MINI.deserialize(built.append('\n').toString());
    }

    /**
     * Who is on the other servers of the network, a line each under the footer: its name, how many, who. Nothing for a
     * server nobody is on, nothing at all when nobody is elsewhere.
     */
    public Component elsewhere(Map<String, ? extends java.util.Collection<String>> byServer) {
        StringBuilder built = new StringBuilder();
        for (Map.Entry<String, ? extends java.util.Collection<String>> server : byServer.entrySet()) {
            if (server.getValue().isEmpty()) {
                continue;
            }
            List<String> names = server.getValue().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            built.append(built.isEmpty() ? "" : "\n").append("<gold>⚔ ").append(escape(server.getKey()))
                    .append(" <gray>(").append(names.size()).append("): <white>")
                    .append(escape(String.join(", ", names)));
        }
        return built.isEmpty() ? Component.empty() : MINI.deserialize(built.toString());
    }

    /**
     * A header or footer a server owner wrote, with the placeholders filled in.
     *
     * <p>Deliberately few of them, and each answers a question somebody actually asks: how many are
     * on, what the server is called, and how many are in each world. A full expression language
     * here would be a second settings system nobody asked for.
     */
    public Component custom(String miniMessage, List<TablistEntry> online, String serverName) {
        int count = online == null ? 0 : online.size();
        String name = serverName == null || serverName.isBlank() ? "This server" : serverName.trim();
        String filled = miniMessage
                .replace("<players>", String.valueOf(count))
                .replace("<server>", escape(name))
                .replace("<worlds>", worldCounts(online))
                .replace("\\n", "\n");
        try {
            return MINI.deserialize(filled);
        } catch (RuntimeException broken) {
            // A header somebody mistyped must not empty the tablist: show it as written, so they
            // can see what they wrote and fix it.
            return Component.text(filled);
        }
    }

    /** "Overworld 3 · Nether 1" — what {@code <worlds>} becomes. */
    private String worldCounts(List<TablistEntry> online) {
        List<TablistGroup> groups = groups(online);
        StringBuilder built = new StringBuilder();
        for (int index = 0; index < groups.size(); index++) {
            if (index > 0) {
                built.append(" · ");
            }
            built.append(escape(groups.get(index).label())).append(' ')
                    .append(groups.get(index).size());
        }
        return built.toString();
    }

    // ------------------------------------------------------------------------ ordering

    /**
     * The scoreboard team name that puts this player where they belong.
     *
     * <p>Two characters of world rank, then the name, then enough of their id to be unique — because
     * two players called the same thing in the same world must not share a team, and sharing one
     * means the second is silently not added.
     */
    public String sortKey(TablistEntry entry) {
        String rank = String.valueOf((char) ('a' + Math.min(9, worldRank(entry.world()))));
        // The rank, inverted so that heavier sorts first — the client orders team names
        // alphabetically and there is no other lever. Two characters, so a hundred ranks fit; more
        // than that is a server with a hierarchy nobody could read off a list anyway.
        int weight = Math.clamp(rankOf(entry), 0, 99);
        String importance = String.format(Locale.ROOT, "%02d", 99 - weight);
        String name = entry.name().toLowerCase(Locale.ROOT);
        String unique = Integer.toHexString(entry.player().hashCode());
        int room = MAX_SORT_KEY - rank.length() - importance.length() - 4;
        String trimmed = name.length() > room ? name.substring(0, Math.max(0, room)) : name;
        String key = rank + importance + trimmed + unique;
        return key.length() > MAX_SORT_KEY ? key.substring(0, MAX_SORT_KEY) : key;
    }

    /**
     * How important somebody is, for the order of the list.
     *
     * <p>Injected rather than read from a permissions plugin: Core does not know what a rank is on
     * any given server, and the one thing worse than an unsorted list is one sorted by somebody
     * else's idea of seniority. Zero for everybody until a plugin says otherwise, which is exactly
     * the behaviour there was before.
     *
     * @param ranks higher sorts nearer the top; 0 to 99
     */
    public void rankOf(ToIntFunction<TablistEntry> ranks) {
        this.ranks = ranks == null ? entry -> 0 : ranks;
    }

    /**
     * One player's rank, and never an exception.
     *
     * <p>The function usually ends up asking a permissions plugin, which may not have loaded, may
     * have been reloaded, or may simply throw. A tablist that vanishes because a rank lookup failed
     * is worse than one in the wrong order.
     */
    private int rankOf(TablistEntry entry) {
        try {
            return ranks.applyAsInt(entry);
        } catch (RuntimeException failure) {
            return 0;
        }
    }

    private volatile ToIntFunction<TablistEntry> ranks = entry -> 0;

    private static String escape(String raw) {
        return Text.literal(raw);
    }

    /**
     * What is shown when the tablist has nobody in it.
     *
     * <p>Looked up each time rather than held: this model outlives any one reload of the message file,
     * and holding a reference taken at construction would pin the wording from before somebody edited
     * it.
     */
    private static String nobodyOn() {
        String builtIn = "<dark_gray>nobody is on";
        if (!RainsCore.isAvailable()) {
            return builtIn;
        }
        Messages words =
                RainsCore.get().messages();
        return words == null ? builtIn : words.raw("tablist.nobody-on");
    }

}
