package de.raindancer.core.ui.hologram;

import de.raindancer.core.ui.chat.Style;

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
    private WelcomeScreen() {
    }

    /** A divider in the server's own brand gradient, dimmed so it frames rather than shouts. */
    private static String rule() {
        return "<gradient:" + Style.brandFrom() + ":" + Style.brandTo() + ">━━━━━━━━━━━━━━━━━━━━</gradient>";
    }

    private static String brand(String text) {
        return "<bold><gradient:" + Style.brandFrom() + ":" + Style.brandTo() + ">" + text + "</gradient></bold>";
    }

    /**
     * Four lines at most: who, a divider, the time and who else is on, then one optional extra — the newest
     * changelog entry, or a tip (/help for a newcomer). Short on purpose: it floats in front of the player.
     */
    static String text(Facts facts) {
        String who = MINI.escapeTags(facts.name());
        StringBuilder text = new StringBuilder(brand((facts.newcomer() ? "✦ Welcome, " : "✦ Welcome back, ") + who + " ✦"));
        text.append('\n').append(rule());
        int hour = facts.now().getHour();
        String sky = hour >= 6 && hour < 18 ? "<#F6B83D>☀</#F6B83D>" : "<#9FB6CC>☾</#9FB6CC>";
        text.append('\n').append(sky).append(" <white>").append(TIME.format(facts.now())).append("</white> <dark_gray>·</dark_gray> ")
                .append(facts.others() == 0 ? "<gray>nobody else online"
                        : "<green>●</green> <white>" + facts.others() + "</white> <gray>other" + (facts.others() == 1 ? "" : "s") + " online");
        if (facts.news() != null && !facts.news().isBlank()) {
            text.append('\n').append(brand("✦")).append(" <white>").append(MINI.escapeTags(facts.news()))
                    .append("</white> <dark_gray>/changelog");
        } else if (facts.newcomer()) {
            text.append("\n<gold>✧</gold> <white>/help</white> <gray>explains everything here");
        } else if (facts.tip() != null) {
            text.append("\n<gold>✧</gold> <white>/").append(facts.tip().command()).append("</white> <gray>").append(facts.tip().does());
        }
        return text.toString();
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
