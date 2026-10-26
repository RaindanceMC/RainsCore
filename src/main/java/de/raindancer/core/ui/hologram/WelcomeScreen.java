package de.raindancer.core.ui.hologram;

import net.kyori.adventure.text.minimessage.MiniMessage;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * What the welcome screen says. Deliberately nothing counted about the viewer — a greeting that knows
 * somebody's hours feels like being watched.
 */
final class WelcomeScreen {

    /** One tip, about one command; shown only where that command exists. */
    record Tip(String command, String does) {
    }

    /**
     * @param news the newest published changelog title, or null
     * @param tip  the tip for now, or null when no tip's command is installed
     */
    record Facts(String name, String serverName, int others, boolean newcomer, ZonedDateTime now, String news, Tip tip) {
    }

    static final List<Tip> TIPS = List.of(
            new Tip("help", "explains everything this server has"),
            new Tip("spawn", "takes you back to spawn"),
            new Tip("sethome", "remembers a place to come back to"),
            new Tip("home", "brings you to your homes"),
            new Tip("tpa", "asks a friend to let you teleport to them"),
            new Tip("msg", "whispers to one player"),
            new Tip("rules", "shows the server's rules"),
            new Tip("warp", "lists the places everybody can go"),
            new Tip("rtp", "drops you somewhere new in the wild"),
            new Tip("balance", "shows your money"),
            new Tip("nick", "gives you a nickname"),
            new Tip("screen", "puts up screens like this one"));

    static final long TIP_SECONDS = 15;

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH);
    private static final String RULE = "<dark_gray>──────────────";

    private WelcomeScreen() {
    }

    static String text(Facts facts) {
        String who = MINI.escapeTags(facts.name());
        String where = "<white>" + MINI.escapeTags(facts.serverName() == null || facts.serverName().isBlank()
                ? "the server" : facts.serverName()) + "</white>";
        StringBuilder text = new StringBuilder(facts.newcomer()
                ? "<gold><bold>Welcome, " + who + "!</bold></gold>\n<gray>Glad you found " + where + "."
                : "<gold><bold>Welcome back, " + who + "!</bold></gold>\n<gray>Good to see you on " + where + " again.");
        text.append('\n').append(RULE);
        text.append("\n<gray>").append(greeting(facts.now().getHour())).append(" — it's <white>")
                .append(TIME.format(facts.now())).append("</white>, ").append(DAY.format(facts.now())).append('.');
        text.append(facts.others() == 0 ? "\n<gray>It's quiet — you're the only one on."
                : "\n<gray><white>" + facts.others() + "</white> other player" + (facts.others() == 1 ? "" : "s")
                + " on right now.");
        if (facts.news() != null && !facts.news().isBlank()) {
            text.append("\n<aqua>New: <white>").append(MINI.escapeTags(facts.news())).append("</white> <dark_gray>(/changelog)");
        }
        if (facts.tip() != null) {
            text.append('\n').append(RULE).append("\n<yellow>Tip: <white>/").append(facts.tip().command())
                    .append("</white> <gray>").append(facts.tip().does());
        }
        if (facts.newcomer()) {
            text.append("\n<gray>New here? Try <white>/help</white>.");
        }
        return text.toString();
    }

    static String greeting(int hour) {
        if (hour >= 5 && hour < 12) {
            return "Good morning";
        }
        if (hour >= 12 && hour < 18) {
            return "Good afternoon";
        }
        if (hour >= 18 && hour < 23) {
            return "Good evening";
        }
        return "Up late";
    }

    static List<Tip> usable(List<Tip> tips, Predicate<String> installed) {
        return tips.stream().filter(tip -> installed.test(tip.command())).toList();
    }

    /** The same tip for everybody at the same moment, the next one every {@link #TIP_SECONDS}. */
    static Tip tip(List<Tip> usable, long epochSecond) {
        if (usable.isEmpty()) {
            return null;
        }
        return usable.get((int) Math.floorMod(epochSecond / TIP_SECONDS, usable.size()));
    }
}
