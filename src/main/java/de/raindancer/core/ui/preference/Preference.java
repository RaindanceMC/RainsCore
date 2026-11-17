package de.raindancer.core.ui.preference;

import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.profile.PlayerSwitch;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One thing a player sets for themselves — a switch, a choice of a few, or a page of its own (the sidebar) — offered
 * through {@link Preferences} so every plugin's end up on the one {@code /preferences} window.
 *
 * @param id      {@code plugin:what}, unique
 * @param group   what it belongs to, as players know it ("Pets", "Chat"); the window sorts by it
 * @param options what a choice can be; empty for a page
 * @param current which option is set now, as an index into {@code options}
 * @param choose  sets the option at that index; also tells whatever draws it
 * @param page    for a page: opens it, with the window as the page Back goes to
 * @param shown   whether this player is offered it at all (staff-only alerts, a server without the feature)
 */
public record Preference(String id, String group, String title, String description, List<String> options,
                         Function<Player, Integer> current, BiConsumer<Player, Integer> choose,
                         BiConsumer<Player, Menu> page, Predicate<Player> shown) {

    private static final List<String> ON_OFF = List.of("On", "Off");

    public Preference {
        if (id == null || !id.matches("[a-z0-9._-]+:[a-z0-9._-]+")) {
            throw new IllegalArgumentException("a preference's id is plugin:what, not " + id);
        }
        group = group == null ? "" : group;
        description = description == null ? "" : description;
        options = options == null ? List.of() : List.copyOf(options);
        shown = shown == null ? player -> true : shown;
        if (page == null && (options.isEmpty() || current == null || choose == null)) {
            throw new IllegalArgumentException("a preference is a choice with options or a page: " + id);
        }
    }

    /** On or off, kept wherever the plugin keeps it. */
    public static Preference toggle(String id, String group, String title, String description,
                                    Predicate<Player> isOn, BiConsumer<Player, Boolean> set) {
        return new Preference(id, group, title, description, ON_OFF, player -> isOn.test(player) ? 0 : 1,
                (player, index) -> set.accept(player, index == 0), null, null);
    }

    /** On or off, kept in a {@link PlayerSwitch}. */
    public static Preference toggle(String id, String group, String title, String description, PlayerSwitch kept) {
        return toggle(id, group, title, description, kept::isOn, kept::set);
    }

    /** One of a few. */
    public static Preference choice(String id, String group, String title, String description, List<String> options,
                                    Function<Player, Integer> current, BiConsumer<Player, Integer> choose) {
        return new Preference(id, group, title, description, options, current, choose, null, null);
    }

    /** A page of its own, for what does not fit a button. */
    public static Preference page(String id, String group, String title, String description,
                                  BiConsumer<Player, Menu> open) {
        return new Preference(id, group, title, description, List.of(), null, null, open, null);
    }

    /** The same, offered only to whoever passes {@code who}. */
    public Preference shownTo(Predicate<Player> who) {
        return new Preference(id, group, title, description, options, current, choose, page, who);
    }

    /** The same, offered only to whoever holds {@code permission}. */
    public Preference needing(String permission) {
        return shownTo(player -> player.hasPermission(permission));
    }

    public boolean isPage() {
        return page != null;
    }

    /** What is set now, as the option's word; empty for a page. */
    public String state(Player player) {
        if (isPage()) {
            return "";
        }
        int at = current.apply(player);
        return options.get(Math.floorMod(at, options.size()));
    }

    /** Moves on to the next option, round to the first after the last. */
    public void next(Player player) {
        if (!isPage()) {
            choose.accept(player, Math.floorMod(current.apply(player) + 1, options.size()));
        }
    }

    boolean offeredTo(Player player) {
        try {
            return shown.test(player);
        } catch (RuntimeException broken) {
            return false;
        }
    }
}
