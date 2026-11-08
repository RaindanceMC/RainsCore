package de.raindancer.core.ui.scoreboard;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A few lines another plugin adds to whichever plugin draws a player's sidebar — a guild's level and bank under the
 * economy's balance — without a second sidebar fighting the first for the one slot the client has.
 *
 * <p>Asked on every redraw, so a section is never stale and never stored. Safe from any thread; a section that
 * throws is left out (and logged once), never taking the sidebar with it.
 *
 * <h2>The player's say (2.58.0)</h2>
 * Every section has a title and a rank. A player hides sections and puts them in their own order
 * ({@link SidebarLayout}, kept by {@link SidebarPreferences}, changed on {@link SidebarMenu}); the title of the
 * sidebar — the server's name — is the one thing that is not theirs. {@link #compose} builds a player's lines that
 * way; {@link #linesFor} is the older shape and does the same.
 */
public final class SidebarSections {

    private static final LogChannel log = Log.of("scoreboard");

    /** A section as registered: whose, what it is called on the menu, where it sits by default. */
    public record Section(String owner, String title, int rank, Size size) {

        public Section(String owner, String title, int rank) {
            this(owner, title, rank, null);
        }
    }

    /**
     * A section the player can make bigger or smaller: how many of something it lists, from {@code min} to
     * {@code max}, {@code fallback} until they choose. {@code unit} names them on the menu: "players".
     */
    public record Size(int min, int max, int fallback, String unit) {

        public Size {
            min = Math.max(1, min);
            max = Math.max(min, max);
            fallback = Math.clamp(fallback, min, max);
        }

        int clamp(int chosen) {
            return Math.clamp(chosen, min, max);
        }
    }

    /** How a section came out on a player's sidebar. */
    public enum Fit { SHOWN, SHORTENED, NO_ROOM, HIDDEN, EMPTY }

    private record Entry(Section section, java.util.function.BiFunction<UUID, Integer, List<Component>> lines) {
    }

    /** The rank of a section registered without one: after the balance, before the boards. */
    public static final int DEFAULT_RANK = 50;

    private static final List<Entry> entries = new CopyOnWriteArrayList<>();
    private static final Map<String, Boolean> complainedAbout = new ConcurrentHashMap<>();

    private SidebarSections() {
    }

    /** Adds a section under {@code owner}; registering again under the same owner replaces it in place. */
    public static synchronized void register(String owner, Function<UUID, List<Component>> lines) {
        register(owner, titleOf(owner), DEFAULT_RANK, lines);
    }

    /**
     * @param title what the section is called on the player's sidebar menu — "Guild", "Lottery pot"
     * @param rank  where it sits before the player moves it: lower comes first
     */
    public static synchronized void register(String owner, String title, int rank, Function<UUID, List<Component>> lines) {
        if (owner == null || lines == null) {
            return;
        }
        put(new Entry(new Section(owner, title == null || title.isBlank() ? titleOf(owner) : title, rank),
                (player, size) -> lines.apply(player)));
    }

    /**
     * A section the player sizes ({@link Size}): {@code lines} gets how many to list. Short of room it is asked again
     * with fewer, down to the smallest, rather than left out.
     */
    public static synchronized void registerSized(String owner, String title, int rank, Size size,
                                                  java.util.function.BiFunction<UUID, Integer, List<Component>> lines) {
        if (owner == null || lines == null || size == null) {
            return;
        }
        put(new Entry(new Section(owner, title == null || title.isBlank() ? titleOf(owner) : title, rank, size),
                lines));
    }

    private static void put(Entry entry) {
        String owner = entry.section().owner();
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).section().owner().equals(owner)) {
                entries.set(index, entry);
                complainedAbout.remove(owner);
                return;
            }
        }
        entries.add(entry);
    }

    private static String titleOf(String owner) {
        String plain = owner.replace('-', ' ').replace('_', ' ').replace('.', ' ').strip();
        return plain.isEmpty() ? owner : Character.toUpperCase(plain.charAt(0)) + plain.substring(1);
    }

    public static synchronized boolean unregister(String owner) {
        complainedAbout.remove(owner);
        return entries.removeIf(entry -> entry.section().owner().equals(owner));
    }

    /** Every section there is, in the server's order — for the menu. */
    public static List<Section> sections() {
        List<Section> all = new ArrayList<>();
        for (Entry entry : entries) {
            all.add(entry.section());
        }
        all.sort(java.util.Comparator.comparingInt(Section::rank).thenComparing(Section::owner));
        return all;
    }

    static List<SidebarLayout.Known> known() {
        List<SidebarLayout.Known> all = new ArrayList<>();
        for (Entry entry : entries) {
            all.add(new SidebarLayout.Known(entry.section().owner(), entry.section().rank()));
        }
        return all;
    }

    /**
     * Every section this player has, each after an empty line, in their order — within {@code room} lines; a
     * section that does not fit whole is left out rather than cut in half.
     */
    public static List<Component> linesFor(UUID player, int room) {
        return build(player, SidebarPreferences.of(player), room, true);
    }

    /** The same without the empty line before the first section: a whole sidebar made of sections. */
    public static List<Component> compose(UUID player, int room) {
        return build(player, SidebarPreferences.of(player), room, false);
    }

    /** For tests, and for a caller that already has the layout. */
    public static List<Component> compose(UUID player, SidebarLayout layout, int room) {
        return build(player, layout, room, false);
    }

    /** How each section comes out on this player's sidebar — for the menu to say what did not fit. */
    public static Map<String, Fit> fits(UUID player, SidebarLayout layout, int room) {
        Map<String, Fit> fits = new java.util.LinkedHashMap<>();
        build(player, layout, room, false, fits);
        return fits;
    }

    private static List<Component> build(UUID player, SidebarLayout layout, int room, boolean gapFirst) {
        return build(player, layout, room, gapFirst, null);
    }

    private static List<Component> build(UUID player, SidebarLayout layout, int room, boolean gapFirst,
                                         Map<String, Fit> fits) {
        List<Component> built = new ArrayList<>();
        if (player == null || room <= 0) {
            return built;
        }
        Map<String, Entry> byOwner = new java.util.HashMap<>();
        for (Entry entry : entries) {
            byOwner.put(entry.section().owner(), entry);
        }
        SidebarLayout mine = layout == null ? SidebarLayout.DEFAULT : layout;
        for (String owner : mine.arrange(known())) {
            Entry entry = byOwner.get(owner);
            if (entry == null) {
                continue;
            }
            if (!mine.shows(owner)) {
                note(fits, owner, Fit.HIDDEN);
                continue;
            }
            Size size = entry.section().size();
            int wanted = size == null ? 0 : size.clamp(mine.sizeOf(owner, size.fallback()));
            boolean gap = gapFirst || !built.isEmpty();
            int left = room - built.size() - (gap ? 1 : 0);
            List<Component> section = null;
            boolean empty = true;
            boolean shortened = false;
            for (int count = wanted; ; count--) {
                List<Component> drawn = lines(entry, owner, player, count);
                if (drawn == null) {
                    break;
                }
                empty = drawn.isEmpty();
                if (empty || drawn.size() <= left) {
                    section = drawn;
                    break;
                }
                if (size == null || count <= size.min()) {
                    break;
                }
                shortened = true;
            }
            if (section == null || section.isEmpty()) {
                note(fits, owner, empty ? Fit.EMPTY : Fit.NO_ROOM);
                continue;
            }
            if (gap) {
                built.add(Component.empty());
            }
            built.addAll(section);
            note(fits, owner, shortened ? Fit.SHORTENED : Fit.SHOWN);
        }
        return built;
    }

    private static List<Component> lines(Entry entry, String owner, UUID player, int count) {
        try {
            List<Component> lines = entry.lines().apply(player, count);
            return lines == null ? List.of() : lines;
        } catch (RuntimeException failed) {
            if (complainedAbout.putIfAbsent(owner, Boolean.TRUE) == null) {
                log.error(failed, "The sidebar section of {} failed and is left out until it works.", owner);
            }
            return null;
        }
    }

    private static void note(Map<String, Fit> fits, String owner, Fit fit) {
        if (fits != null) {
            fits.put(owner, fit);
        }
    }

    /** Drops the sections whose code came from {@code loader}. @return how many */
    public static synchronized int forgetFrom(ClassLoader loader) {
        if (loader == null) {
            return 0;
        }
        List<Entry> gone = entries.stream().filter(entry -> entry.lines().getClass().getClassLoader() == loader).toList();
        entries.removeAll(gone);
        gone.forEach(entry -> complainedAbout.remove(entry.section().owner()));
        return gone.size();
    }

    /** For tests. */
    public static synchronized void clear() {
        entries.clear();
        complainedAbout.clear();
    }

    static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
