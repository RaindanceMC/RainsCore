package de.raindancer.core.ui.hologram;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.stats.ServerStats;
import de.raindancer.core.platform.stats.StatsHistory;
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
import java.time.LocalDate;
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

    private static final int COLUMNS = 36;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH);

    /** The server's health and history: status now, charts of the last hours, the record of players at once. */
    public static void registerStats(Screens screens, Plugin core, Server server, ServerStats stats) {
        screens.register(core, ScreenKind.everyone("server-status", "Server status",
                "Ticks per second, tick time, memory, processor and players — right now, with meters.",
                Material.REDSTONE_LAMP, () -> status(server, stats)));
        screens.register(core, ScreenKind.everyone("chart-tps", "TPS chart", "Ticks per second over the last six hours.",
                Material.REPEATER, () -> tpsChart(stats)));
        screens.register(core, ScreenKind.everyone("chart-memory", "Memory chart", "Memory in use over the last six hours.",
                Material.CHEST_MINECART, () -> memoryChart(stats)));
        screens.register(core, ScreenKind.everyone("chart-cpu", "Processor chart",
                "How much of the processor the server took over the last six hours.", Material.OBSERVER, () -> cpuChart(stats)));
        screens.register(core, ScreenKind.everyone("chart-players", "Players chart",
                "Players online over the last 24 hours, and today's peak.", Material.PLAYER_HEAD, () -> playersChart(server, stats)));
        screens.register(core, ScreenKind.everyone("player-record", "Player record",
                "Most players ever on at once, and today's peak and visitors.", Material.GOLDEN_HELMET, () -> record(server, stats)));
    }

    private static long minuteNow() {
        return System.currentTimeMillis() / 60_000;
    }

    private static String axis(String from) {
        return "\n<dark_gray>" + from + "                                    now";
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static Component status(Server server, ServerStats stats) {
        double tps = stats.tps();
        double mspt = stats.mspt();
        Runtime runtime = Runtime.getRuntime();
        double usedGb = (runtime.totalMemory() - runtime.freeMemory()) / 1073741824.0;
        double maxGb = runtime.maxMemory() / 1073741824.0;
        double ram = usedGb * 100 / maxGb;
        double cpu = ServerStats.cpu();
        String name = RainsCore.get().serverName();
        StringBuilder text = new StringBuilder("<gold><bold>" + MINI.escapeTags(name == null || name.isBlank() ? "Server" : name)
                + "</bold></gold>");
        text.append("\n<gray>TPS ").append(Double.isNaN(tps) ? "<gray>not measured here"
                : Charts.meter(tps / 20, Charts.tpsColour(tps)) + " " + Charts.tpsColour(tps) + number(tps));
        if (!Double.isNaN(mspt)) {
            text.append("\n<gray>Tick <white>").append(number(mspt)).append(" ms");
        }
        text.append("\n<gray>RAM ").append(Charts.meter(ram / 100, Charts.loadColour(ram))).append(" <white>")
                .append(String.format(Locale.ROOT, "%.1f/%.1f GB", usedGb, maxGb));
        if (!Double.isNaN(cpu)) {
            text.append("\n<gray>CPU ").append(Charts.meter(cpu / 100, Charts.loadColour(cpu))).append(" <white>")
                    .append(Math.round(cpu)).append("%");
        }
        text.append("\n<gray>Players <white>").append(visible(server).size()).append("/").append(server.getMaxPlayers())
                .append(" <dark_gray>· <gray>up <white>")
                .append(Times.brief(Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime())));
        return MINI.deserialize(text.toString());
    }

    private static Component tpsChart(ServerStats stats) {
        long now = minuteNow();
        double[] lows = stats.history().columns(StatsHistory.Sample::tps, now - 360, now + 1, COLUMNS, StatsHistory.Mode.LOWEST);
        double tps = stats.tps();
        if (Double.isNaN(tps)) {
            return MINI.deserialize("<gold><bold>TPS</bold></gold>\n<gray>Not measured on this server");
        }
        double lowest = java.util.Arrays.stream(lows).filter(v -> !Double.isNaN(v)).min().orElse(tps);
        return MINI.deserialize("<gold><bold>TPS</bold></gold> <gray>· last 6 hours\n"
                + Charts.spark(lows, 0, 20, Charts::tpsColour) + axis("-6h")
                + "\n<gray>now " + Charts.tpsColour(tps) + number(tps) + " <dark_gray>· <gray>lowest "
                + Charts.tpsColour(lowest) + number(lowest));
    }

    private static Component memoryChart(ServerStats stats) {
        long now = minuteNow();
        double[] used = stats.history().columns(StatsHistory.Sample::ramPercent, now - 360, now + 1, COLUMNS,
                StatsHistory.Mode.HIGHEST);
        StatsHistory.Sample latest = stats.history().latest();
        double highest = java.util.Arrays.stream(used).filter(v -> !Double.isNaN(v)).max().orElse(0);
        String nowText = latest == null ? "" : String.format(Locale.ROOT, "%.1f/%.1f GB", latest.ramUsed() / 1024.0,
                latest.ramMax() / 1024.0);
        return MINI.deserialize("<gold><bold>Memory</bold></gold> <gray>· last 6 hours\n"
                + Charts.spark(used, 0, 100, Charts::loadColour) + axis("-6h")
                + "\n<gray>now <white>" + nowText + " <dark_gray>· <gray>highest " + Charts.loadColour(highest)
                + Math.round(highest) + "%");
    }

    private static Component cpuChart(ServerStats stats) {
        long now = minuteNow();
        double[] load = stats.history().columns(StatsHistory.Sample::cpu, now - 360, now + 1, COLUMNS,
                StatsHistory.Mode.AVERAGE);
        double cpu = ServerStats.cpu();
        if (Double.isNaN(cpu)) {
            return MINI.deserialize("<gold><bold>Processor</bold></gold>\n<gray>Not measured on this server");
        }
        double highest = java.util.Arrays.stream(load).filter(v -> !Double.isNaN(v)).max().orElse(cpu);
        return MINI.deserialize("<gold><bold>Processor</bold></gold> <gray>· last 6 hours\n"
                + Charts.spark(load, 0, 100, Charts::loadColour) + axis("-6h")
                + "\n<gray>now " + Charts.loadColour(cpu) + Math.round(cpu) + "% <dark_gray>· <gray>highest "
                + Charts.loadColour(highest) + Math.round(highest) + "%");
    }

    private static long todayFrom() {
        return LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() / 60_000;
    }

    private static Component playersChart(Server server, ServerStats stats) {
        long now = minuteNow();
        double[] most = stats.history().columns(sample -> sample.players(), now - 1440, now + 1, COLUMNS,
                StatsHistory.Mode.HIGHEST);
        double top = Math.max(1, java.util.Arrays.stream(most).filter(v -> !Double.isNaN(v)).max().orElse(1));
        int peak = Math.max(stats.history().peakSince(todayFrom()), visible(server).size());
        return MINI.deserialize("<gold><bold>Players</bold></gold> <gray>· last 24 hours\n"
                + Charts.spark(most, 0, top, value -> "<aqua>") + axis("-24h")
                + "\n<gray>now <white>" + visible(server).size() + " <dark_gray>· <gray>peak today <white>" + peak);
    }

    private static Component record(Server server, ServerStats stats) {
        StatsHistory.Record record = stats.history().record();
        int peak = Math.max(stats.history().peakSince(todayFrom()), visible(server).size());
        int visitors = stats.history().playersOn(LocalDate.now());
        StringBuilder text = new StringBuilder("<gold><bold>Player record</bold></gold>");
        if (record.players() == 0) {
            text.append("\n<gray>Nobody yet");
        } else {
            text.append("\n<gray>Most at once <gold><bold>").append(record.players()).append("</bold></gold>")
                    .append("\n<dark_gray>").append(WHEN.format(Instant.ofEpochMilli(record.at()).atZone(ZoneId.systemDefault())));
        }
        text.append("\n<gray>Today: peak <white>").append(peak).append(" <dark_gray>· <white>").append(visitors)
                .append(" <gray>player").append(visitors == 1 ? "" : "s");
        return MINI.deserialize(text.toString());
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
