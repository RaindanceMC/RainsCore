package de.raindancer.core.world.spawn;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Things lying about to be picked up — a power-up in the middle of a map, a health pack — each coming back a while
 * after somebody took it. Only the bookkeeping: {@link PickupShow} puts them in the world and notices who walks into
 * one; what a pickup <em>does</em> is the caller's, by {@link Pickup#kind()}.
 *
 * <p>Thread safety: synchronized; asked from region threads.
 */
public final class Pickups {

    /** One pickup spot. {@code kind} is the caller's own name for what it gives. */
    public record Pickup(String id, String kind, String world, double x, double y, double z, Duration respawn) {
        public Pickup {
            if (id == null || id.isBlank() || kind == null || world == null) {
                throw new IllegalArgumentException("a pickup needs an id, a kind and a world");
            }
            respawn = respawn == null || respawn.isNegative() ? Duration.ZERO : respawn;
        }

        public Pickup as(String newKind) {
            return new Pickup(id, newKind, world, x, y, z, respawn);
        }
    }

    private final LongSupplier clock;
    private final Map<String, Pickup> all = new LinkedHashMap<>();
    private final Map<String, Long> backAt = new LinkedHashMap<>();

    public Pickups(LongSupplier clock) {
        this.clock = clock;
    }

    /** Puts one down (or replaces the one with the same id), there to be taken at once. */
    public synchronized void place(Pickup pickup) {
        all.put(pickup.id(), pickup);
        backAt.remove(pickup.id());
    }

    public synchronized void remove(String id) {
        all.remove(id);
        backAt.remove(id);
    }

    /** Takes it if it is there; empty when it is gone already — two players reaching it in one tick get one. */
    public synchronized Optional<Pickup> take(String id) {
        Pickup pickup = all.get(id);
        if (pickup == null || backAt.containsKey(id)) {
            return Optional.empty();
        }
        backAt.put(id, clock.getAsLong() + pickup.respawn().toMillis());
        return Optional.of(pickup);
    }

    /** The ones whose time is up since the last ask; each is reported once and is there again. */
    public synchronized List<Pickup> dueBack() {
        long now = clock.getAsLong();
        List<Pickup> back = new ArrayList<>();
        backAt.entrySet().removeIf(entry -> {
            if (entry.getValue() > now) {
                return false;
            }
            Pickup pickup = all.get(entry.getKey());
            if (pickup != null) {
                back.add(pickup);
            }
            return true;
        });
        return back;
    }

    public synchronized boolean isAvailable(String id) {
        return all.containsKey(id) && !backAt.containsKey(id);
    }

    public synchronized List<Pickup> available() {
        return all.values().stream().filter(pickup -> !backAt.containsKey(pickup.id())).toList();
    }

    public synchronized List<Pickup> all() {
        return List.copyOf(all.values());
    }

    public synchronized Optional<Duration> backIn(String id) {
        Long at = backAt.get(id);
        return at == null ? Optional.empty() : Optional.of(Duration.ofMillis(Math.max(0, at - clock.getAsLong())));
    }

    /** The available pickup nearest to a spot, within {@code reach} blocks. */
    public synchronized Optional<Pickup> within(String world, double x, double y, double z, double reach) {
        double most = reach * reach;
        return all.values().stream()
                .filter(pickup -> !backAt.containsKey(pickup.id()) && pickup.world().equals(world))
                .filter(pickup -> distanceSquared(pickup, x, y, z) <= most)
                .min(Comparator.comparingDouble(pickup -> distanceSquared(pickup, x, y, z)));
    }

    private static double distanceSquared(Pickup pickup, double x, double y, double z) {
        double dx = pickup.x() - x;
        double dy = pickup.y() - y;
        double dz = pickup.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public synchronized void clear() {
        all.clear();
        backAt.clear();
    }
}
