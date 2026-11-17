package de.raindancer.core.ui.preference;

import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Every plugin's {@link Preference}s, for the one {@code /preferences} window: health bars, vein mining, the sidebar,
 * pets, teleport requests… instead of a command for each. Static and process-wide, like {@code CosmeticDoors}.
 */
public final class Preferences {

    private static final List<Preference> offered = new ArrayList<>();

    private Preferences() {
    }

    /** Offers one; a second with the same id is ignored. {@code owner} is not used yet beyond documenting who. */
    public static void offer(Plugin owner, Preference preference) {
        if (preference == null) {
            return;
        }
        synchronized (offered) {
            if (offered.stream().noneMatch(each -> each.id().equals(preference.id()))) {
                offered.add(preference);
            }
        }
    }

    public static void withdraw(Preference preference) {
        synchronized (offered) {
            offered.removeIf(each -> each == preference || each.id().equals(preference.id()));
        }
    }

    /** What this player is offered, by group and then title. */
    public static List<Preference> forPlayer(Player player) {
        List<Preference> all;
        synchronized (offered) {
            all = List.copyOf(offered);
        }
        return all.stream().filter(each -> each.offeredTo(player))
                .sorted(Comparator.comparing((Preference each) -> each.group().toLowerCase(Locale.ROOT))
                        .thenComparing(each -> each.title().toLowerCase(Locale.ROOT)))
                .toList();
    }

    /** Drops whatever {@code loader}'s code offered — for a plugin disabled without withdrawing. */
    public static int forgetFrom(ClassLoader loader) {
        synchronized (offered) {
            int before = offered.size();
            offered.removeIf(each -> PluginCode.isFrom(each.choose() != null ? each.choose() : each.page(), loader));
            return before - offered.size();
        }
    }

    public static void clear() {
        synchronized (offered) {
            offered.clear();
        }
    }
}
