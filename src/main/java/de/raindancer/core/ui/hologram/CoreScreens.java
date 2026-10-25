package de.raindancer.core.ui.hologram;

import de.raindancer.core.RainsCore;
import de.raindancer.core.social.presence.Playtime;
import de.raindancer.core.ui.changelog.ChangelogEntry;
import de.raindancer.core.world.time.Times;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** The screens Core itself can fill: who is on, the server, the time, what's new, and everybody's own profile. */
public final class CoreScreens {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int MOST_NAMES = 16;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter JOINED = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private CoreScreens() {
    }

    public static void register(Screens screens, Plugin core, Server server) {
        screens.register(core, ScreenKind.personal("welcome", "Welcome", "Greets everybody by name, with how long they have played here.",
                Material.OAK_SIGN, viewer -> welcome(viewer)));
        screens.register(core, ScreenKind.personal("profile", "Your profile", "Everybody's own: playtime, how much of it active, and when they first came.",
                Material.PLAYER_HEAD, viewer -> profile(server, viewer)));
        screens.register(core, ScreenKind.personal("playtime-you", "Your playtime", "Everybody's own time played on the network.",
                Material.CLOCK, viewer -> playtime(viewer)));
        screens.register(core, ScreenKind.everyone("online", "Who is online", "How many are on and who.",
                Material.TOTEM_OF_UNDYING, () -> online(server)));
        screens.register(core, ScreenKind.everyone("server", "This server", "Its name, how many are on and how long it has been up.",
                Material.BEACON, () -> serverInfo(server)));
        screens.register(core, ScreenKind.everyone("clock", "Date and time", "Today's date and the time, as the server has it.",
                Material.CLOCK, CoreScreens::clock));
        screens.register(core, ScreenKind.everyone("news", "What's new", "The latest published changelog entry.",
                Material.WRITABLE_BOOK, CoreScreens::news));
    }

    private static Playtime playtime() {
        return RainsCore.get().playtime();
    }

    private static Component welcome(ScreenKind.Viewer viewer) {
        Playtime playtime = playtime();
        long minutes = playtime == null ? 0 : playtime.minutes(viewer.id());
        String name = MINI.escapeTags(viewer.name());
        if (minutes < 5) {
            return MINI.deserialize("<gold><bold>Welcome, " + name + "!</bold></gold>\n<gray>Glad you're here. Try <white>/help</white>.");
        }
        return MINI.deserialize("<gold><bold>Welcome back, " + name + "!</bold></gold>\n<gray>You've played <white>"
                + Times.describe(Duration.ofMinutes(minutes)) + "</white> here.");
    }

    private static Component profile(Server server, ScreenKind.Viewer viewer) {
        Playtime playtime = playtime();
        StringBuilder text = new StringBuilder("<gold><bold>" + MINI.escapeTags(viewer.name()) + "</bold></gold>");
        if (playtime != null) {
            text.append("\n<gray>Played <white>").append(Times.describe(playtime.played(viewer.id())))
                    .append("</white>\n<gray>Active <white>").append(Times.describe(playtime.playedActively(viewer.id())))
                    .append("</white>");
        }
        OfflinePlayer offline = server.getOfflinePlayer(viewer.id());
        long first = offline.getFirstPlayed();
        if (first > 0) {
            text.append("\n<gray>Here since <white>")
                    .append(JOINED.format(Instant.ofEpochMilli(first).atZone(ZoneId.systemDefault()))).append("</white>");
        }
        return MINI.deserialize(text.toString());
    }

    private static Component playtime(ScreenKind.Viewer viewer) {
        Playtime playtime = playtime();
        if (playtime == null) {
            return MINI.deserialize("<gray>Playtime is not counted here.");
        }
        return MINI.deserialize("<gold><bold>Your playtime</bold></gold>\n<white>"
                + Times.describe(playtime.played(viewer.id())) + "\n<gray>of it active: <white>"
                + Times.describe(playtime.playedActively(viewer.id())));
    }

    private static List<Player> visible(Server server) {
        var vanish = RainsCore.get().vanish();
        return server.getOnlinePlayers().stream()
                .filter(player -> vanish == null || !vanish.isVanished(player.getUniqueId()))
                .map(Player.class::cast).toList();
    }

    private static Component online(Server server) {
        List<Player> on = visible(server);
        StringBuilder text = new StringBuilder("<gold><bold>Online</bold></gold> <white>" + on.size()
                + "</white><gray>/" + server.getMaxPlayers());
        if (on.isEmpty()) {
            text.append("\n<gray>Nobody right now");
        }
        List<String> names = on.stream().map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        for (int i = 0; i < Math.min(MOST_NAMES, names.size()); i++) {
            text.append(i % 2 == 0 ? "\n<white>" : " <dark_gray>·</dark_gray> <white>").append(MINI.escapeTags(names.get(i)));
        }
        if (names.size() > MOST_NAMES) {
            text.append("\n<gray>and ").append(names.size() - MOST_NAMES).append(" more");
        }
        return MINI.deserialize(text.toString());
    }

    private static Component serverInfo(Server server) {
        String name = RainsCore.get().serverName();
        Duration up = Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime());
        return MINI.deserialize("<gold><bold>" + MINI.escapeTags(name == null || name.isBlank() ? "Server" : name)
                + "</bold></gold>\n<gray>Online <white>" + visible(server).size() + "</white>/" + server.getMaxPlayers()
                + "\n<gray>Up for <white>" + Times.describe(up));
    }

    private static Component clock() {
        ZonedDateTime now = ZonedDateTime.now();
        return MINI.deserialize("<gold><bold>" + TIME.format(now) + "</bold></gold>\n<gray>" + DAY.format(now));
    }

    private static Component news() {
        var changelog = RainsCore.get().changelog();
        List<ChangelogEntry> published = changelog == null ? List.of() : changelog.published();
        if (published.isEmpty()) {
            return MINI.deserialize("<gold><bold>What's new</bold></gold>\n<gray>Nothing yet");
        }
        ChangelogEntry latest = published.getFirst();
        StringBuilder text = new StringBuilder("<gold><bold>What's new</bold></gold>\n<white>")
                .append(MINI.escapeTags(latest.title()));
        latest.lines().stream().limit(4).forEach(line -> text.append("\n<gray>• ").append(MINI.escapeTags(line)));
        return MINI.deserialize(text.toString());
    }
}
