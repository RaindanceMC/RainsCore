package de.raindancer.core.world.companion;

import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * One companion out in the world: a mob a plugin summoned for a player, which follows them, fights for them as
 * them (see {@code Combat.companions()}) and can be ridden. Held by the plugin that summoned it; changed through
 * {@link CompanionService}.
 */
public final class Companion {

    private final UUID id = UUID.randomUUID();
    private final Plugin plugin;
    private final UUID owner;
    private final CompanionHooks hooks;
    private volatile CompanionSpec spec;
    private volatile UUID mob;
    private volatile boolean staying;
    volatile UUID target;
    volatile long lastAttack;
    volatile long lastHeal;
    volatile long ticks;
    volatile double unreportedRide;
    volatile boolean gone;
    final Wandering wandering = new Wandering();
    volatile org.bukkit.Location exploring;
    volatile org.bukkit.Location stayAt;

    Companion(Plugin plugin, UUID owner, CompanionSpec spec, CompanionHooks hooks) {
        this.plugin = plugin;
        this.owner = owner;
        this.spec = spec;
        this.hooks = hooks == null ? new CompanionHooks() { } : hooks;
    }

    public UUID id() {
        return id;
    }

    public Plugin plugin() {
        return plugin;
    }

    public UUID owner() {
        return owner;
    }

    public CompanionSpec spec() {
        return spec;
    }

    void spec(CompanionSpec changed) {
        spec = changed;
    }

    /** The mob it is right now; a new one after it follows its owner into another world. */
    public UUID mob() {
        return mob;
    }

    void mob(UUID made) {
        mob = made;
    }

    public CompanionHooks hooks() {
        return hooks;
    }

    /** Whether it holds its ground instead of following. */
    public boolean staying() {
        return staying;
    }

    public void stay(boolean on) {
        staying = on;
        stayAt = null;
    }

    public boolean isGone() {
        return gone;
    }
}
