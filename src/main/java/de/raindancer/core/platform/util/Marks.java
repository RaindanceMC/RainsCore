package de.raindancer.core.platform.util;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which rows a store still has to write, and how to take that list without losing an entry.
 *
 * <h2>The mistake this exists to stop</h2>
 * Every store here keeps a set of the keys that have changed, so a save writes the rows that changed
 * rather than every row it has. The obvious way to write them is:
 *
 * <pre>{@code
 * Set<K> writing = Set.copyOf(changed);   // snapshot
 * write(writing);
 * changed.removeAll(writing);             // and clear
 * }</pre>
 *
 * <p>That loses any change which arrives <em>while the write is running</em>. The key is already in
 * the snapshot, so marking it again is a no-op on the set; then {@code removeAll} takes the mark
 * away. The new value is never written, and nothing will write it again until somebody happens to
 * touch that row once more — which for a warp somebody renamed, or a ban somebody lifted, may be
 * never.
 *
 * <p>It is not a narrow window either. These writes go to disk, they are called from a timer, and
 * players are changing things the whole time.
 *
 * <h2>What this does instead</h2>
 * Takes each mark <em>off</em> the set as it collects it. A change arriving during the write puts its
 * key back, and the next save picks it up. The worst case is one row written twice, which costs
 * nothing.
 *
 * <p>Found by a second review (agy) on 2026-08-03, in five stores at once — which is the argument for
 * it living in one place rather than being written out five times.
 */
public final class Marks {

    private Marks() {
    }

    /**
     * Takes every mark off the set and hands them back.
     *
     * <p>One mark at a time, and only the ones actually removed. A mark added while this is running is
     * either taken now or left for next time — never both, and never neither.
     *
     * @param marks the live set, which this empties
     * @return what was taken, in the order it was found
     */
    public static <T> Set<T> drain(Set<T> marks) {
        Set<T> taken = new LinkedHashSet<>();
        if (marks == null) {
            return taken;
        }
        for (T mark : marks) {
            if (marks.remove(mark)) {
                taken.add(mark);
            }
        }
        return taken;
    }

    /**
     * Takes the marks off like {@link #drain}, putting each into {@code holding} before it leaves
     * {@code marks} — so at no instant is a mark in neither. A store whose refresh keeps what is marked or
     * held would otherwise, in that instant, take the database's older value over a change still being written.
     */
    public static <T> Set<T> drainInto(Set<T> marks, Set<T> holding) {
        Set<T> taken = new LinkedHashSet<>();
        if (marks == null) {
            return taken;
        }
        for (T mark : marks) {
            holding.add(mark);
            if (marks.remove(mark)) {
                taken.add(mark);
            }
        }
        return taken;
    }

    /**
     * Puts marks back after a write that did not happen.
     *
     * <p>The other half of the pattern, and the half that is easy to leave out: marks dropped after a
     * failed write are rows nothing will ever write again.
     */
    public static <T> void restore(Set<T> marks, Set<T> taken) {
        if (marks != null && taken != null) {
            marks.addAll(taken);
        }
    }
}
