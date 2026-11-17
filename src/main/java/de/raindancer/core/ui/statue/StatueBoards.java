package de.raindancer.core.ui.statue;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * Every board statues can stand for, by id: Economy's rankings, Prime's members, Ranks' creators. A plugin offers
 * its boards when it starts and withdraws them when it stops; they go with its classes either way. Static and
 * process-wide, like {@code TravelDoors}.
 *
 * <p>A statue whose board is not offered right now is left exactly as it stands: its plugin may simply not have
 * started yet.
 */
public final class StatueBoards {

    private static final LogChannel log = Log.of("core");

    private record Offered(ClassLoader loader, StatueBoard board) {
    }

    private static final Map<String, Offered> BOARDS = new TreeMap<>();

    private StatueBoards() {
    }

    /** Offers a board; the newest offer of an id wins. */
    public static void offer(Plugin owner, StatueBoard board) {
        offer(owner == null ? board.standings().getClass().getClassLoader() : owner.getClass().getClassLoader(),
                board);
    }

    /** @param loader the offering plugin's class loader, for {@link #forgetFrom} */
    public static synchronized void offer(ClassLoader loader, StatueBoard board) {
        if (board != null) {
            BOARDS.put(board.id(), new Offered(loader, board));
        }
    }

    /** Withdraws this very board; a newer offer of the same id stays. */
    public static synchronized void withdraw(StatueBoard board) {
        if (board != null) {
            BOARDS.values().removeIf(each -> each.board() == board);
        }
    }

    public static synchronized Optional<StatueBoard> find(String id) {
        Offered found = id == null ? null : BOARDS.get(id);
        return found == null ? Optional.empty() : Optional.of(found.board());
    }

    public static synchronized List<String> ids() {
        return new ArrayList<>(BOARDS.keySet());
    }

    public static synchronized List<StatueBoard> all() {
        return BOARDS.values().stream().map(Offered::board).toList();
    }

    /**
     * Who is on a board right now, best first; empty when it is not offered or failed to answer. May take a moment:
     * off the server thread.
     */
    public static List<StatueBoard.Standing> standings(String id) {
        Optional<StatueBoard> board = find(id);
        if (board.isEmpty()) {
            return List.of();
        }
        try {
            List<StatueBoard.Standing> standings = board.get().standings().get();
            return standings == null ? List.of()
                    : standings.stream().filter(Objects::nonNull).toList();
        } catch (RuntimeException failed) {
            log.debug("The {} statue board did not answer: {}", id, failed.getMessage());
            return List.of();
        }
    }

    /**
     * People by id as standings with their names, in the order given; somebody whose name is not known (never on
     * this server) is left out, there is no skin or name to show for them yet.
     */
    public static List<StatueBoard.Standing> named(List<UUID> players, Function<UUID, Optional<String>> nameOf) {
        List<StatueBoard.Standing> named = new ArrayList<>();
        for (UUID player : players) {
            if (player != null) {
                nameOf.apply(player).filter(name -> !name.isBlank())
                        .ifPresent(name -> named.add(new StatueBoard.Standing(player, name, null)));
            }
        }
        return named;
    }

    /** {@link #named(List, Function)} with the names this server knows: online, or remembered by Core. */
    public static List<StatueBoard.Standing> named(org.bukkit.Server server, List<UUID> players) {
        return named(players, id -> de.raindancer.core.platform.command.PlayerTargets.nameOf(server, id));
    }

    /** Lets go of every board a plugin's classes offered; how many went. */
    public static synchronized int forgetFrom(ClassLoader loader) {
        int before = BOARDS.size();
        BOARDS.values().removeIf(each -> each.loader() == loader);
        return before - BOARDS.size();
    }

    public static synchronized void clear() {
        BOARDS.clear();
    }
}
