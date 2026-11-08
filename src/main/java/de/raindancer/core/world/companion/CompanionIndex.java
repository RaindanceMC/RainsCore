package de.raindancer.core.world.companion;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Companions found by owner and by place, so the brain asks "who is next to me" and "who are my owner's" without
 * walking everybody's. Cells are 16 blocks square; a companion sits in the cell of where it last said it stood
 * ({@link #moved}). Safe from any thread — brains run on different region threads under Folia.
 */
final class CompanionIndex {

    record Cell(UUID world, int x, int z) {
    }

    private static final int SHIFT = 4;

    private final Map<UUID, List<Companion>> byOwner = new ConcurrentHashMap<>();
    private final Map<Cell, Set<Companion>> byCell = new ConcurrentHashMap<>();

    void add(Companion companion) {
        byOwner.compute(companion.owner(), (owner, list) -> {
            List<Companion> all = list == null ? new CopyOnWriteArrayList<>() : list;
            if (!all.contains(companion)) {
                all.add(companion);
            }
            return all;
        });
    }

    void remove(Companion companion) {
        byOwner.computeIfPresent(companion.owner(), (owner, list) -> {
            list.remove(companion);
            return list.isEmpty() ? null : list;
        });
        leave(companion);
    }

    /** An owner's companions, live: a snapshot to iterate while some are taken away. Never copied per call. */
    List<Companion> of(UUID owner) {
        List<Companion> found = byOwner.get(owner);
        return found == null ? List.of() : Collections.unmodifiableList(found);
    }

    /** It now stands at {@code here}. Ignored for a companion that is no longer in the index. */
    void moved(Companion companion, Location here) {
        companion.at = here;
        World world = here.getWorld();
        if (world == null || !byOwner.getOrDefault(companion.owner(), List.of()).contains(companion)) {
            return;
        }
        Cell now = new Cell(world.getUID(), here.getBlockX() >> SHIFT, here.getBlockZ() >> SHIFT);
        Cell before = companion.cell;
        if (now.equals(before)) {
            return;
        }
        byCell.computeIfAbsent(now, cell -> ConcurrentHashMap.newKeySet()).add(companion);
        companion.cell = now;
        if (before != null) {
            drop(before, companion);
        }
    }

    /**
     * Calls back for every companion whose recorded spot is in the cells that touch the square of {@code radius}
     * around {@code centre} — a superset of those within the radius, so the caller still checks the distance.
     */
    void near(Location centre, double radius, Consumer<Companion> each) {
        World world = centre.getWorld();
        if (world == null) {
            return;
        }
        int minX = (int) Math.floor(centre.getX() - radius) >> SHIFT;
        int maxX = (int) Math.floor(centre.getX() + radius) >> SHIFT;
        int minZ = (int) Math.floor(centre.getZ() - radius) >> SHIFT;
        int maxZ = (int) Math.floor(centre.getZ() + radius) >> SHIFT;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Set<Companion> here = byCell.get(new Cell(world.getUID(), x, z));
                if (here == null) {
                    continue;
                }
                for (Companion companion : here) {
                    if (companion.gone) {
                        here.remove(companion);
                    } else {
                        each.accept(companion);
                    }
                }
            }
        }
    }

    List<Companion> near(Location centre, double radius) {
        List<Companion> found = new ArrayList<>();
        near(centre, radius, found::add);
        return found;
    }

    int size() {
        return byOwner.values().stream().mapToInt(List::size).sum();
    }

    private void leave(Companion companion) {
        Cell cell = companion.cell;
        companion.cell = null;
        if (cell != null) {
            drop(cell, companion);
        }
    }

    private void drop(Cell cell, Companion companion) {
        byCell.computeIfPresent(cell, (key, set) -> {
            set.remove(companion);
            return set.isEmpty() ? null : set;
        });
    }
}
