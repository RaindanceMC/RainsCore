package de.raindancer.core.moderation.maintenance;

import de.raindancer.core.ui.screen.LeaveScreen;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** What somebody turned away during maintenance reads. */
public final class MaintenanceText {

    /** The reason that reads as Tom's update message rather than as typed. */
    public static final String UPDATE = "update";

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** How long everybody online is warned before those not allowed are sent off: an update a minute. */
    public static long graceMillis(String reason) {
        return isUpdate(reason) ? 60_000 : 20_000;
    }

    /**
     * Which second to announce now that the countdown went from {@code before} to {@code now} seconds left: where it
     * started ({@code before} -1), or a mark (60, 30, 10) passed in between; -1 for none.
     */
    public static long crossed(long before, long now) {
        if (before < 0) {
            return now;
        }
        for (long mark : new long[]{60, 30, 10}) {
            if (before > mark && now <= mark) {
                return mark;
            }
        }
        return -1;
    }

    /**
     * The chat line for this moment of the countdown, or null for none: when it starts and at 60, 30 and 10 seconds
     * left. Worded and coloured like the tellraw lines the Lilly deploy scripts sent, which players know.
     */
    public static Component countdown(String reason, long secondsLeft, boolean first) {
        if (!first && secondsLeft != 60 && secondsLeft != 30 && secondsLeft != 10) {
            return null;
        }
        boolean update = isUpdate(reason);
        String what;
        NamedTextColor colour = NamedTextColor.YELLOW;
        if (secondsLeft <= 10) {
            what = (update ? "Restart" : "Maintenance") + " in " + secondsLeft + " seconds - "
                    + (update ? "see you in a minute!" : "see you soon!");
            colour = NamedTextColor.RED;
        } else if (update) {
            what = secondsLeft >= 60 ? "Restart in " + secondsLeft + " seconds for an update!"
                    : "Restart in " + secondsLeft + " seconds.";
        } else {
            what = "Maintenance in " + secondsLeft + " seconds"
                    + (first && reason != null && !reason.isBlank() ? ": " + reason.strip() : ".");
        }
        return Component.text()
                .append(Component.text("[Server] ", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(what, colour))
                .build();
    }

    /** An update's expected length as the whole minutes it is announced with: rounded up, 1 to 240. */
    public static int updateMinutes(long expectedMillis) {
        return (int) Math.clamp((expectedMillis + 59_999) / 60_000, 1, 240);
    }

    public static boolean isUpdate(String reason) {
        return UPDATE.equalsIgnoreCase(reason == null ? "" : reason.strip());
    }

    private MaintenanceText() {
    }

    /**
     * @param backAt when it should be over (0 for not said); counted from {@code now}, not shown as a clock time,
     *               because players are in other time zones than the server
     */
    public static Component closed(String reason, long backAt, long now) {
        if (isUpdate(reason)) {
            return LeaveScreen.framed(MINI.deserialize("<gold><bold>We're updating</bold></gold>\n\n<gray>We'll be back <white>"
                    + whenBack(backAt, now) + "</white>. Join again then!"));
        }
        String why = reason == null || reason.isBlank() ? "" : "\n\n<white>" + MINI.escapeTags(reason.strip());
        return LeaveScreen.framed(MINI.deserialize("<gold><bold>Under maintenance</bold></gold>" + why
                + "\n\n<gray>Come back a little later."));
    }

    /** "in about 3 minutes", "any moment now", "soon". */
    public static String whenBack(long backAt, long now) {
        if (backAt <= 0) {
            return "soon";
        }
        long minutes = (backAt - now + 59_999) / 60_000;
        return minutes <= 0 ? "any moment now" : minutes == 1 ? "in about a minute" : "in about " + minutes + " minutes";
    }
}
