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
    volatile long lastHurt;
    volatile int cooldown;
    int side = 1;
    final Legs.State legs = new Legs.State();
    /** Where its mob stood this tick — for the others to keep clear of, without reaching into its entity. */
    volatile org.bukkit.Location at;
    /** Its place among its owner's companions, and how many there are: which spot behind them is its own. */
    volatile int slot;
    volatile int slots = 1;
    volatile float slotYaw;
    /** It has come to a stop — resting, sitting, at a spot it explores. */
    volatile boolean resting;
    /** The companion it is cuddling up to, and the one cuddling up to it (ids), and till when. */
    volatile UUID cuddling;
    volatile UUID cuddledBy;
    volatile long cuddleUntil;
    /** Somebody else's companion it is squaring up to, and till when. */
    volatile UUID squabbling;
    volatile long squabbleUntil;
    final Progress chase = new Progress();
    final java.util.Map<UUID, Long> gaveUp = new java.util.concurrent.ConcurrentHashMap<>();

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
