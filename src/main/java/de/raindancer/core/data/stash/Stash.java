package de.raindancer.core.data.stash;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Somebody's stash of items: a list of stacks, up to a capacity, and four armour stands. The model behind
 * a personal vault, a claim's store or anything else a player puts items into and takes them back out of;
 * {@link StashStore} keeps it on disk.
 *
 * <p>Nothing outside ever holds a stack that is in here — everything goes in and comes out as a copy —
 * so a screen showing the stash cannot change it by accident, and a stale screen cannot take the same
 * stack twice: {@link #take} only hands something out when the entry is still the one that was drawn.
 *
 * <p>Synchronised, because the owner's own thread changes it while the save runs on another.
 */
public final class Stash {

    /**
     * Everything that has to reach the disk, as of one moment.
     *
     * @param kept    encoded items this server could not read when the stash was loaded — written back
     *                untouched, so a missing mod or a downgrade does not quietly delete them
     * @param version moves on with every change, so a store can tell an older picture from a newer one
     */
    public record Contents(List<ItemStack> items, Map<ArmourPiece, ItemStack> armour, List<String> kept,
                           long version) {
    }

    private final boolean stands;
    private int capacity;
    private final List<ItemStack> items = new ArrayList<>();
    private final Map<ArmourPiece, ItemStack> armour = new EnumMap<>(ArmourPiece.class);
    private final List<String> kept = new ArrayList<>();
    private long version;

    public Stash(int capacity) {
        this(capacity, true);
    }

    private Stash(int capacity, boolean stands) {
        this.capacity = Math.max(1, capacity);
        this.stands = stands;
    }

    /** A stash with no armour stands: a helmet is an item like any other. */
    public static Stash withoutStands(int capacity) {
        return new Stash(capacity, false);
    }

    /**
     * {@link #restored(int, List, Map, List, long)} for a stash without stands. Anything the file still has on a
     * stand comes back as an item, so switching a stash to no stands never loses a piece.
     */
    public static Stash restoredWithoutStands(int capacity, List<ItemStack> items, Map<ArmourPiece, ItemStack> armour,
                                              List<String> kept, long version) {
        Stash stash = new Stash(capacity, false);
        stash.fill(items, Map.of(), kept, version);
        armour.values().forEach(item -> {
            if (!isNothing(item)) {
                stash.items.add(item.clone());
            }
        });
        return stash;
    }

    /** Rebuilds a stash from what was on disk, without counting as a change. */
    public static Stash restored(int capacity, List<ItemStack> items, Map<ArmourPiece, ItemStack> armour,
                                 List<String> kept) {
        return restored(capacity, items, armour, kept, 0);
    }

    /**
     * @param version where the count of changes carries on from — the version last written, so that a stash
     *                loaded again is not taken for an older picture by the store that wrote it
     */
    public static Stash restored(int capacity, List<ItemStack> items, Map<ArmourPiece, ItemStack> armour,
                                 List<String> kept, long version) {
        Stash stash = new Stash(capacity);
        stash.fill(items, armour, kept, version);
        return stash;
    }

    private void fill(List<ItemStack> items, Map<ArmourPiece, ItemStack> armour, List<String> kept, long version) {
        this.version = Math.max(0, version);
        for (ItemStack item : items) {
            if (!isNothing(item)) {
                // Not deposit(): a stash saved under a bigger capacity keeps everything, and stacks are
                // left as the owner arranged them.
                this.items.add(item.clone());
            }
        }
        armour.forEach((piece, item) -> {
            if (!isNothing(item)) {
                this.armour.put(piece, item.clone());
            }
        });
        this.kept.addAll(kept);
    }

    /**
     * Puts a copy of this in: a piece of armour on its empty stand, everything else topping up similar
     * stacks first and then starting new ones while there is room.
     *
     * @return how many were taken; the caller removes exactly that many from wherever it came from
     */
    public synchronized int deposit(ItemStack item) {
        if (isNothing(item)) {
            return 0;
        }
        int left = item.getAmount();
        int max = Math.max(1, item.getMaxStackSize());

        Optional<ArmourPiece> piece = ArmourPiece.of(item.getType());
        if (stands && piece.isPresent() && !armour.containsKey(piece.get())) {
            int placed = Math.min(left, max);
            armour.put(piece.get(), item.asQuantity(placed));
            left -= placed;
        }
        for (ItemStack stack : items) {
            if (left <= 0) {
                break;
            }
            if (stack.getAmount() < max && stack.isSimilar(item)) {
                int added = Math.min(left, max - stack.getAmount());
                stack.setAmount(stack.getAmount() + added);
                left -= added;
            }
        }
        while (left > 0 && items.size() < capacity) {
            int placed = Math.min(left, max);
            items.add(item.asQuantity(placed));
            left -= placed;
        }
        int accepted = item.getAmount() - left;
        if (accepted > 0) {
            version++;
        }
        return accepted;
    }

    /**
     * Takes an entry out, if it is still exactly what the screen showed.
     *
     * @return the stack, or null when the entry has gone or changed since it was drawn
     */
    public synchronized ItemStack take(int index, ItemStack shown) {
        if (index < 0 || index >= items.size() || shown == null) {
            return null;
        }
        ItemStack there = items.get(index);
        if (!there.isSimilar(shown) || there.getAmount() != shown.getAmount()) {
            return null;
        }
        items.remove(index);
        version++;
        return there;
    }

    /** Takes the first entry that matches out, or null when none does. */
    public synchronized ItemStack takeFirst(Predicate<ItemStack> which) {
        for (int index = 0; index < items.size(); index++) {
            if (which.test(items.get(index).clone())) {
                version++;
                return items.remove(index);
            }
        }
        return null;
    }

    public synchronized boolean contains(Predicate<ItemStack> which) {
        for (ItemStack item : items) {
            if (which.test(item.clone())) {
                return true;
            }
        }
        return false;
    }

    /** Takes whatever is on this stand off it, or null when it is empty. */
    public synchronized ItemStack takeArmour(ArmourPiece piece) {
        ItemStack there = armour.remove(piece);
        if (there != null) {
            version++;
        }
        return there;
    }

    /**
     * Puts this on the stand and hands back what was on it — or, when it is not that kind of armour,
     * hands the item itself straight back and changes nothing.
     *
     * @param item null to empty the stand
     */
    public synchronized ItemStack swapArmour(ArmourPiece piece, ItemStack item) {
        if (!isNothing(item) && ArmourPiece.of(item.getType()).orElse(null) != piece) {
            return item;
        }
        ItemStack before = isNothing(item) ? armour.remove(piece) : armour.put(piece, item.clone());
        if (before != null || !isNothing(item)) {
            version++;
        }
        return before;
    }

    public synchronized List<ItemStack> items() {
        List<ItemStack> copies = new ArrayList<>(items.size());
        items.forEach(item -> copies.add(item.clone()));
        return copies;
    }

    public synchronized Optional<ItemStack> armour(ArmourPiece piece) {
        ItemStack there = armour.get(piece);
        return there == null ? Optional.empty() : Optional.of(there.clone());
    }

    public synchronized boolean hasArmour() {
        return !armour.isEmpty();
    }

    public synchronized boolean isEmpty() {
        return items.isEmpty() && armour.isEmpty();
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized int capacity() {
        return capacity;
    }

    /**
     * How many stacks fit from now on — for a vault that gains pages. A smaller capacity takes nothing away: what is
     * in stays in, and only a deposit is refused until it is under the limit again.
     */
    public synchronized void capacity(int stacks) {
        capacity = Math.max(1, stacks);
    }

    public boolean hasStands() {
        return stands;
    }

    /**
     * Tops up every part stack from the similar ones after it, then puts the entries in this order (stable, so equal
     * ones keep theirs).
     *
     * @return whether anything moved; nothing moved is no change and nothing to write
     */
    public synchronized boolean sort(Comparator<ItemStack> order) {
        List<ItemStack> merged = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            ItemStack left = item.clone();
            int max = Math.max(1, left.getMaxStackSize());
            for (ItemStack into : merged) {
                if (left.getAmount() <= 0) {
                    break;
                }
                if (into.getAmount() < max && into.isSimilar(left)) {
                    int moved = Math.min(left.getAmount(), max - into.getAmount());
                    into.setAmount(into.getAmount() + moved);
                    left.setAmount(left.getAmount() - moved);
                }
            }
            if (left.getAmount() > 0) {
                merged.add(left);
            }
        }
        merged.sort(order);
        if (sameAs(merged)) {
            return false;
        }
        items.clear();
        items.addAll(merged);
        version++;
        return true;
    }

    private boolean sameAs(List<ItemStack> other) {
        if (other.size() != items.size()) {
            return false;
        }
        for (int index = 0; index < other.size(); index++) {
            ItemStack mine = items.get(index);
            ItemStack theirs = other.get(index);
            if (mine.getAmount() != theirs.getAmount() || !mine.isSimilar(theirs)) {
                return false;
            }
        }
        return true;
    }

    public synchronized Contents contents() {
        Map<ArmourPiece, ItemStack> stands = new EnumMap<>(ArmourPiece.class);
        armour.forEach((piece, item) -> stands.put(piece, item.clone()));
        return new Contents(items(), Collections.unmodifiableMap(stands), List.copyOf(kept), version);
    }

    private static boolean isNothing(ItemStack item) {
        return item == null || item.isEmpty();
    }
}
