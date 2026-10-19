package de.raindancer.core.ui.identity;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Small badges drawn in front of a player's name in chat and the tablist: a guild's tag, a clan's.
 *
 * <p>Not {@link Identities#setPrefix}: that is one slot, owned by whoever writes ranks, and a second plugin
 * writing it would overwrite the rank. A badge is <em>asked for</em> each time a name is drawn, so it is
 * never stale and never stored, and any number of plugins can each have one.
 *
 * <p>A badge function is called on the chat thread, the tablist refresh and the nametag pass, so it must be
 * cheap and safe from any thread. One that throws is skipped (and logged once), never taking the chat line
 * with it.
 *
 * <p>{@link #register} badges show in chat and the tablist only — what they always did. A badge that should
 * also float above the head, or be left out of one place, is {@link #registerPlaced} and asked per
 * {@link Place}.
 *
 * <p>A badge registered with a label is <em>wearable</em>: the one {@link #wardrobe} (Cosmetics' Tags page) may
 * take it off for a player who does not want it. Badges without a label (a server's mode, say) always show.
 */
public final class NameBadges {

    private static final LogChannel log = Log.of("identity");

    /** Where a name is being drawn. */
    public enum Place { CHAT, TABLIST, NAMETAG }

    /** {@code source} is the caller's own function — what {@link #forgetFrom} judges the plugin by. */
    private record Entry(String owner, String label, BiFunction<UUID, Place, Optional<Component>> badge,
                         Object source) {
    }

    /** A badge a player may choose to wear, as a menu names it. */
    public record Wearable(String owner, String label) {
    }

    /** Whether {@code player} wears the wearable badge of {@code owner}; asked as often as badges are. */
    private static volatile BiPredicate<UUID, String> wardrobe;

    private static final List<Entry> entries = new CopyOnWriteArrayList<>();
    private static final Map<String, Boolean> complainedAbout = new ConcurrentHashMap<>();

    private NameBadges() {
    }

    /** Adds a badge under {@code owner}; registering again under the same owner replaces it in place. */
    public static void register(String owner, Function<UUID, Optional<Component>> badge) {
        register(owner, null, badge);
    }

    /** As {@link #register(String, Function)}, wearable under {@code label} (null: always worn). */
    public static void register(String owner, String label, Function<UUID, Optional<Component>> badge) {
        if (badge == null) {
            return;
        }
        put(new Entry(owner, blankToNull(label),
                (player, place) -> place == Place.NAMETAG ? Optional.empty() : badge.apply(player), badge));
    }

    /**
     * Adds a badge asked for each {@link Place} separately — empty where it should not show. Same owner
     * namespace as {@link #register}: either replaces the other.
     */
    public static void registerPlaced(String owner, BiFunction<UUID, Place, Optional<Component>> badge) {
        registerPlaced(owner, null, badge);
    }

    /** As {@link #registerPlaced(String, BiFunction)}, wearable under {@code label} (null: always worn). */
    public static void registerPlaced(String owner, String label, BiFunction<UUID, Place, Optional<Component>> badge) {
        if (badge == null) {
            return;
        }
        put(new Entry(owner, blankToNull(label), badge, badge));
    }

    private static String blankToNull(String label) {
        return label == null || label.isBlank() ? null : label.strip();
    }

    /** Sets the one wardrobe deciding who wears which wearable badge; null puts everything back on. */
    public static void wardrobe(BiPredicate<UUID, String> wears) {
        wardrobe = wears;
    }

    public static boolean hasWardrobe() {
        return wardrobe != null;
    }

    /** The wearable badges, in the order they are drawn. */
    public static List<Wearable> wearables() {
        return entries.stream().filter(entry -> entry.label() != null)
                .map(entry -> new Wearable(entry.owner(), entry.label())).toList();
    }

    /** Whether the wardrobe lets {@code player} wear {@code owner}'s badge; true without a wardrobe or when it fails. */
    public static boolean wears(UUID player, String owner) {
        BiPredicate<UUID, String> current = wardrobe;
        if (current == null) {
            return true;
        }
        try {
            return current.test(player, owner);
        } catch (RuntimeException failed) {
            if (complainedAbout.putIfAbsent("wardrobe", Boolean.TRUE) == null) {
                log.error(failed, "The name badge wardrobe failed; every badge is worn until it works.");
            }
            return true;
        }
    }

    /** {@code owner}'s badge for {@code player} whether they wear it or not — for a menu to show it. */
    public static Optional<Component> preview(UUID player, String owner, Place place) {
        for (Entry entry : entries) {
            if (entry.owner().equals(owner)) {
                try {
                    Optional<Component> badge = entry.badge().apply(player, place);
                    return badge == null ? Optional.empty() : badge;
                } catch (RuntimeException failed) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static synchronized void put(Entry entry) {
        String owner = entry.owner();
        if (owner == null) {
            return;
        }
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).owner().equals(owner)) {
                entries.set(index, entry);
                complainedAbout.remove(owner);
                return;
            }
        }
        entries.add(entry);
    }

    public static synchronized boolean unregister(String owner) {
        complainedAbout.remove(owner);
        return entries.removeIf(entry -> entry.owner().equals(owner));
    }

    public static List<String> owners() {
        return entries.stream().map(Entry::owner).toList();
    }

    /** Every badge this player has in chat, each followed by a space; empty when there are none. */
    public static Component before(UUID player) {
        return before(player, Place.CHAT);
    }

    /** Every badge this player has where they are being drawn, each followed by a space. */
    public static Component before(UUID player, Place place) {
        if (player == null || place == null || entries.isEmpty()) {
            return Component.empty();
        }
        List<Component> found = new ArrayList<>(2);
        for (Entry entry : entries) {
            if (entry.label() != null && !wears(player, entry.owner())) {
                continue;
            }
            try {
                Optional<Component> badge = entry.badge().apply(player, place);
                if (badge != null && badge.isPresent()) {
                    found.add(badge.get());
                }
            } catch (RuntimeException failed) {
                if (complainedAbout.putIfAbsent(entry.owner(), Boolean.TRUE) == null) {
                    log.error(failed, "The name badge of {} failed and is left out of names until it works.",
                            entry.owner());
                }
            }
        }
        Component built = Component.empty();
        for (Component badge : found) {
            built = built.append(badge).append(Component.space());
        }
        return built;
    }

    /** Drops the badges whose code came from {@code loader}. @return how many */
    public static synchronized int forgetFrom(ClassLoader loader) {
        if (loader == null) {
            return 0;
        }
        List<Entry> gone = entries.stream()
                .filter(entry -> entry.source().getClass().getClassLoader() == loader)
                .toList();
        entries.removeAll(gone);
        gone.forEach(entry -> complainedAbout.remove(entry.owner()));
        BiPredicate<UUID, String> current = wardrobe;
        if (current != null && current.getClass().getClassLoader() == loader) {
            wardrobe = null;
            complainedAbout.remove("wardrobe");
            return gone.size() + 1;
        }
        return gone.size();
    }

    /** For tests. */
    public static synchronized void clear() {
        entries.clear();
        complainedAbout.clear();
        wardrobe = null;
    }
}
