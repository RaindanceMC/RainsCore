package de.raindancer.core.ui.board;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Every ranking on the server, by id — so a statue, a podium or a screen can show any plugin's board without
 * knowing the plugin. A plugin registers its boards when it starts; they go with it when its classes are let go.
 *
 * <p>Reading never throws: a board whose plugin fails to answer reads as empty, and a reader shows nobody rather
 * than breaking.
 */
public final class Leaderboards {

    private static final LogChannel log = Log.of("core");

    private record Registered(ClassLoader loader, Leaderboard board) {
    }

    private static final Map<String, Registered> BOARDS = new TreeMap<>();

    private Leaderboards() {
    }

    /** @param loader the registering plugin's class loader — {@code getClass().getClassLoader()} */
    public static synchronized void register(ClassLoader loader, Leaderboard board) {
        BOARDS.put(board.id(), new Registered(loader, board));
    }

    public static synchronized boolean unregister(String id) {
        return BOARDS.remove(id) != null;
    }

    public static synchronized Optional<Leaderboard> find(String id) {
        Registered found = BOARDS.get(id);
        return found == null ? Optional.empty() : Optional.of(found.board());
    }

    public static synchronized List<String> ids() {
        return new ArrayList<>(BOARDS.keySet());
    }

    public static synchronized List<Leaderboard> all() {
        return BOARDS.values().stream().map(Registered::board).toList();
    }

    /** The best {@code shown} of a board; empty when it is unknown or failed to answer. */
    public static List<Leaderboard.Standing> top(String id, int shown) {
        Optional<Leaderboard> board = find(id);
        if (board.isEmpty() || shown <= 0) {
            return List.of();
        }
        try {
            List<Leaderboard.Standing> standings = board.get().top().get();
            if (standings == null) {
                return List.of();
            }
            return List.copyOf(standings.subList(0, Math.min(shown, standings.size())));
        } catch (RuntimeException failed) {
            log.debug("The {} board did not answer: {}", id, failed.getMessage());
            return List.of();
        }
    }

    /** Who holds {@code place} (1 is the top) on a board, if anybody. */
    public static Optional<Leaderboard.Standing> place(String id, int place) {
        List<Leaderboard.Standing> top = top(id, place);
        return place >= 1 && top.size() >= place ? Optional.of(top.get(place - 1)) : Optional.empty();
    }

    /** Lets go of every board a plugin's classes registered; how many went. */
    public static synchronized int forgetFrom(ClassLoader loader) {
        int before = BOARDS.size();
        BOARDS.values().removeIf(each -> each.loader() == loader);
        return before - BOARDS.size();
    }

    public static synchronized void clear() {
        BOARDS.clear();
    }
}
