package de.raindancer.core.ui.scoreboard;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Who gets a player's sidebar.
 *
 * <h2>What this adds to the copied-in FastBoard</h2>
 * FastBoard solves the hard half: it writes the scoreboard packets directly, so the sidebar does not
 * flicker, can be written from any thread, and does not fight other plugins over Bukkit's team API.
 * What it deliberately leaves alone is <em>who gets the sidebar</em> — not a library's business, but
 * very much this server's, because a player has one sidebar and the claims module wanting to show
 * whose land this is while a ghast flight wants to show its progress is exactly the collision the
 * action bar had. Same answer as there: an owner, a priority, and whatever was interrupted comes
 * back when the winner goes away.
 *
 * <h2>A sidebar is decoration</h2>
 * FastBoard is raw reflection into the server's internals, so on a Paper build it does not recognise
 * even <em>touching</em> the class throws {@link ExceptionInInitializerError} out of a static block.
 * Unwrapped, that takes down whichever listener touched it. Nothing about a sidebar is worth a
 * broken plugin, so every call here is allowed to degrade to doing nothing — once, loudly, in the
 * log, and then quietly, because a plugin ticking a sidebar on a server that cannot draw one must
 * not cost a reflection failure every tick.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread, which on Folia means every thread. The same discipline as
 * {@link de.raindancer.core.ui.actionbar.ActionBars}: a {@link ConcurrentHashMap} of per-player slots,
 * each mutated only under its own lock, and a writer re-checks the map under that lock so a slot
 * removed underneath it cannot be resurrected as an orphan.
 */
public final class Scoreboards {

    private static final LogChannel log = Log.of("scoreboard");

    /** One plugin's claim on one player's sidebar. */
    private record Claim(Sidebar sidebar, ScoreboardPriority priority, long order) {

        /** Higher priority first; on a tie the more recent, so the newest answer shows. */
        boolean beats(Claim other) {
            if (other == null) {
                return true;
            }
            int byPriority = Integer.compare(priority.ordinal(), other.priority.ordinal());
            return byPriority != 0 ? byPriority > 0 : order >= other.order;
        }
    }

    /** What one player is being shown, and by whom. Everything here is under the slot's monitor. */
    private static final class Slot {
        private final Map<String, Claim> byOwner = new LinkedHashMap<>();
        private Board board;
        /** What was last drawn, so an unchanged sidebar costs nothing. */
        private Sidebar onScreen;
        private String showingOwner;
    }

    private final BoardFactory factory;
    private final Map<UUID, Slot> slots = new ConcurrentHashMap<>();
    private final AtomicBoolean unavailable = new AtomicBoolean();
    /** Orders claims without a clock: only their relative order matters. */
    private final AtomicLong sequence =
            new AtomicLong();

    public Scoreboards(BoardFactory factory) {
        this.factory = factory;
    }

    /**
     * Whether sidebars work on this server at all.
     *
     * <p>False once a board has failed to be created, which on a Paper build FastBoard does not
     * recognise is the first attempt. Worth showing in a diagnostic command: "the sidebar is off
     * because this server's internals are not recognised" is a better answer than silence.
     */
    public boolean isAvailable() {
        return !unavailable.get();
    }

    // -------------------------------------------------------------------------- showing

    /**
     * Shows a sidebar, or replaces the one this owner is already showing.
     *
     * <p>Meant to be called freely — every tick if that is convenient. An unchanged sidebar sends
     * nothing, and an owner that is not currently winning is remembered without disturbing whoever
     * is.
     *
     * @param player   whose sidebar
     * @param owner    who is asking: a plugin or subsystem name, never blank
     * @param sidebar  what to show; {@code null} clears this owner instead
     * @param priority how badly it is wanted
     */
    public void show(UUID player, String owner, Sidebar sidebar, ScoreboardPriority priority) {
        if (player == null || unavailable.get()) {
            return;
        }
        String who = owner == null ? "" : owner.trim();
        if (who.isEmpty()) {
            // A blank owner would be a bucket every careless caller shared, so one plugin's sidebar
            // would silently replace another's and clearing would take away both.
            log.warn("A sidebar for {} was refused: it named no owner.", player);
            return;
        }
        if (sidebar == null) {
            clear(player, who);
            return;
        }
        Claim claim = new Claim(sidebar, priority == null ? ScoreboardPriority.NORMAL : priority,
                sequence.incrementAndGet());
        while (true) {
            Slot slot = slots.computeIfAbsent(player, key -> new Slot());
            synchronized (slot) {
                if (slots.get(player) != slot) {
                    // Removed between the lookup and the lock; whatever is there now is somebody
                    // else's slot. Same race the action bar had, same fix.
                    continue;
                }
                slot.byOwner.put(who, claim);
                repaint(player, slot);
                return;
            }
        }
    }

    /** Takes away what this owner was showing. Harmless when it was showing nothing. */
    public void clear(UUID player, String owner) {
        if (player == null || owner == null) {
            return;
        }
        Slot slot = slots.get(player);
        if (slot == null) {
            return;
        }
        synchronized (slot) {
            if (slots.get(player) != slot || slot.byOwner.remove(owner.trim()) == null) {
                return;
            }
            repaint(player, slot);
        }
    }

    /** Drops everything for a player and takes their board away. Called when they log out. */
    public void forget(UUID player) {
        if (player == null) {
            return;
        }
        Slot slot = slots.remove(player);
        if (slot == null) {
            return;
        }
        synchronized (slot) {
            slot.byOwner.clear();
            deleteBoard(player, slot);
        }
    }

    /** Takes every board away. Called from {@code onDisable}, so a reload leaves nothing behind. */
    public void shutdown() {
        for (UUID player : Set.copyOf(slots.keySet())) {
            forget(player);
        }
    }

    // ---------------------------------------------------------------------------- reading

    /** Whether anything of ours is on this player's sidebar. */
    public boolean isShowingAnything(UUID player) {
        Slot slot = player == null ? null : slots.get(player);
        if (slot == null) {
            return false;
        }
        synchronized (slot) {
            return winnerOf(slot) != null;
        }
    }

    /** Which plugin currently owns this player's sidebar, for a diagnostic command. */
    public Optional<String> ownerOf(UUID player) {
        Slot slot = player == null ? null : slots.get(player);
        if (slot == null) {
            return Optional.empty();
        }
        synchronized (slot) {
            return Optional.ofNullable(slot.showingOwner);
        }
    }

    /** Which players we are holding a board for. */
    public Set<UUID> trackedPlayers() {
        return Set.copyOf(slots.keySet());
    }

    /** Every owner currently claiming this player's sidebar. */
    public List<String> ownersFor(UUID player) {
        Slot slot = player == null ? null : slots.get(player);
        if (slot == null) {
            return List.of();
        }
        synchronized (slot) {
            return List.copyOf(new ArrayList<>(slot.byOwner.keySet()));
        }
    }

    /**
     * Takes this player's board away and draws what they should see on a fresh one — for when the way to draw
     * for them has changed, which is what a client mod saying hello a moment after joining is. Who claims the
     * sidebar is untouched.
     */
    public void redraw(UUID player) {
        Slot slot = slots.get(player);
        if (slot == null) {
            return;
        }
        synchronized (slot) {
            if (slots.get(player) != slot) {
                return;
            }
            if (slot.board != null) {
                try {
                    slot.board.delete();
                } catch (RuntimeException | LinkageError failure) {
                    log.debug("Could not remove {}'s sidebar: {}", player, String.valueOf(failure));
                }
            }
            slot.board = null;
            slot.onScreen = null;
            slot.showingOwner = null;
            repaint(player, slot);
        }
    }

    // -------------------------------------------------------------------------- internals

    /** Draws whatever wins, creating or deleting the board as that changes. Under the slot lock. */
    private void repaint(UUID player, Slot slot) {
        Map.Entry<String, Claim> winner = winnerOf(slot);
        if (winner == null) {
            deleteBoard(player, slot);
            slots.remove(player, slot);
            return;
        }
        Sidebar wanted = winner.getValue().sidebar();
        if (wanted.equals(slot.onScreen) && slot.board != null) {
            return;
        }
        Board board = boardFor(player, slot);
        if (board == null) {
            return;
        }
        try {
            board.update(wanted.title(), wanted.lines());
            slot.onScreen = wanted;
            slot.showingOwner = winner.getKey();
        } catch (RuntimeException | LinkageError failure) {
            // The board is in an unknown state now, so it is dropped rather than kept and hoped
            // for. The next show() will make a fresh one.
            log.warn(failure instanceof Exception cause ? cause : null,
                    "Could not draw {}'s sidebar; it has been taken away.", player);
            dropBoard(player, slot);
        }
    }

    /** The player's board, made on first use. Null when this server cannot draw one. */
    private Board boardFor(UUID player, Slot slot) {
        if (slot.board != null) {
            return slot.board;
        }
        try {
            slot.board = factory.create(player);
            if (slot.board == null) {
                // They are not there any more; nothing about the server is wrong.
                slot.byOwner.clear();
                slots.remove(player, slot);
            }
            return slot.board;
        } catch (RuntimeException | LinkageError failure) {
            // LinkageError as well as RuntimeException on purpose: FastBoard fails in a static
            // initialiser on an unrecognised server, which arrives as ExceptionInInitializerError
            // and then NoClassDefFoundError — neither of which is an Exception.
            unavailable.set(true);
            log.error("Sidebars are unavailable on this server, so none will be shown. This "
                    + "usually means the server's internals are newer than the scoreboard code "
                    + "copied into this plugin: {}", String.valueOf(failure));
            slot.byOwner.clear();
            slots.remove(player, slot);
            return null;
        }
    }

    private void deleteBoard(UUID player, Slot slot) {
        if (slot.board == null) {
            return;
        }
        try {
            slot.board.delete();
        } catch (RuntimeException | LinkageError failure) {
            // Nothing useful to do: the player is usually already gone, which is why it threw.
            log.debug("Could not remove {}'s sidebar: {}", player, String.valueOf(failure));
        }
        dropBoard(player, slot);
    }

    private void dropBoard(UUID player, Slot slot) {
        slot.board = null;
        slot.onScreen = null;
        slot.showingOwner = null;
        slot.byOwner.clear();
        slots.remove(player, slot);
    }

    private static Map.Entry<String, Claim> winnerOf(Slot slot) {
        Map.Entry<String, Claim> best = null;
        for (Map.Entry<String, Claim> candidate : slot.byOwner.entrySet()) {
            if (best == null || candidate.getValue().beats(best.getValue())) {
                best = candidate;
            }
        }
        return best;
    }
}
