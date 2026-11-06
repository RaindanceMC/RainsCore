package de.raindancer.core.world.companion;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Who hurt an owner a moment ago, and whom an owner hit — what a companion goes for. Forgotten after a while. */
public final class Grudges {

    /** How long a hit is remembered, in milliseconds. */
    public static final long MEMORY = 10_000;

    private record Hit(UUID other, long at) {
    }

    private final Map<UUID, Hit> hurtBy = new ConcurrentHashMap<>();
    private final Map<UUID, Hit> struck = new ConcurrentHashMap<>();

    public void hurt(UUID owner, UUID by, long now) {
        hurtBy.put(owner, new Hit(by, now));
    }

    public void struck(UUID owner, UUID victim, long now) {
        struck.put(owner, new Hit(victim, now));
    }

    public Optional<UUID> hurtBy(UUID owner, long now) {
        return fresh(hurtBy.get(owner), now);
    }

    public Optional<UUID> struck(UUID owner, long now) {
        return fresh(struck.get(owner), now);
    }

    public boolean hurtOwner(UUID owner, UUID other, long now) {
        return hurtBy(owner, now).filter(other::equals).isPresent();
    }

    public boolean ownerStruck(UUID owner, UUID other, long now) {
        return struck(owner, now).filter(other::equals).isPresent();
    }

    public void forget(UUID owner) {
        hurtBy.remove(owner);
        struck.remove(owner);
    }

    private static Optional<UUID> fresh(Hit hit, long now) {
        return hit == null || now - hit.at() > MEMORY ? Optional.empty() : Optional.of(hit.other());
    }
}
