package de.raindancer.core.world.combat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mobs a plugin bound to a player — a pet fox, a summoned golem — which are not {@code Tameable} and so have no owner
 * the server knows of. {@link CombatListener} traces a bound mob back to its owner exactly like a tamed wolf: what it
 * bites, its owner bit, and hurting it is hurting its owner. Kept in memory only, so whoever binds a mob releases it
 * when the mob goes. Safe from any thread.
 */
public final class Companions {

    private final Map<UUID, UUID> owners = new ConcurrentHashMap<>();

    public void bind(UUID mob, UUID owner) {
        if (mob != null && owner != null) {
            owners.put(mob, owner);
        }
    }

    public void release(UUID mob) {
        if (mob != null) {
            owners.remove(mob);
        }
    }

    /** @return how many were released */
    public int releaseAllOf(UUID owner) {
        int before = owners.size();
        owners.values().removeIf(owner::equals);
        return before - owners.size();
    }

    public Optional<UUID> ownerOf(UUID mob) {
        return mob == null ? Optional.empty() : Optional.ofNullable(owners.get(mob));
    }

    public boolean isCompanion(UUID mob) {
        return mob != null && owners.containsKey(mob);
    }

    public List<UUID> of(UUID owner) {
        return owners.entrySet().stream().filter(entry -> entry.getValue().equals(owner)).map(Map.Entry::getKey).toList();
    }
}
