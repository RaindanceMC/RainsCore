package de.raindancer.core.social.support;

import org.bukkit.permissions.Permission;

/**
 * Who a server thanks: coffee supporters ({@link Supporters}), or, on a network without them (Core's setting
 * {@code coffee-supporters} off), its Prime members. Plugins ask here for the words and the command instead of
 * writing "supporters" and "/support" themselves. Static and process-wide; RainsCore keeps it in step with the
 * setting.
 */
public final class Thanks {

    private static volatile boolean supporters = true;

    private Thanks() {
    }

    public static boolean supportersOn() {
        return supporters;
    }

    /** Set by RainsCore from its setting. */
    public static void supporters(boolean on) {
        supporters = on;
    }

    /** Who the thanks go to, in a sentence: "for supporters", "for Prime members". */
    public static String who() {
        return supporters ? "supporters" : "Prime members";
    }

    /** Where to read how to become one. */
    public static String command() {
        return supporters ? "/support" : "/prime";
    }

    /**
     * Without coffee supporters, {@code rains.supporter} is a child of {@code rains.prime}: every perk a plugin hung
     * on the supporter node is then Prime's, without the plugin knowing.
     */
    public static void linkToPrime(Permission prime, boolean supportersOn) {
        if (supportersOn) {
            prime.getChildren().remove(SupporterPermission.NODE);
        } else {
            prime.getChildren().put(SupporterPermission.NODE, true);
        }
    }
}
