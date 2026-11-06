package de.raindancer.core.world.companion;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.combat.Combat;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Breedable;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Companions: mobs a plugin summons for a player — pets, mounts, summoned helpers. Core makes the mob (smaller than
 * its wild self, never saved with its chunk, its own AI off), drives it every tick (follow, fight, throw, mend, guard,
 * be ridden — flying ones fly), keeps it out of trouble (no leads, breeding, picking up, griefing, burning in the
 * sun) and takes it away when its owner leaves or vanishes — bringing it back when they reappear and following them
 * into another world. The plugin only says what it is and hears what happened, through {@link CompanionHooks}; it
 * touches no mob.
 *
 * <pre>{@code
 * Companion fred = core.companions().summon(plugin, player,
 *         CompanionSpec.of(EntityType.WITHER).height(0.58).flies(true).fighting(Fighting.RANGED)
 *                 .missile(EntityType.WITHER_SKULL).name(Component.text("Nuclear Fred")), hooks).orElseThrow();
 * }</pre>
 */
public final class CompanionService {

    public enum Refusal {
        /** The owner is vanished. */
        VANISHED,
        /** The mob could not be made there. */
        FAILED,
        /** Not a mob. */
        NOT_A_MOB
    }

    public record Summoned(Companion companion, Refusal refusal) {

        public Optional<Companion> get() {
            return Optional.ofNullable(companion);
        }
    }

    private final Plugin core;
    private final Server server;
    private final Combat combat;
    private final Predicate<UUID> vanished;
    private final Map<UUID, Companion> byId = new ConcurrentHashMap<>();
    private final Map<UUID, Companion> byMob = new ConcurrentHashMap<>();
    /** Mobs let go of but not yet taken out of the world — until then nothing may hurt or kill them. */
    private final java.util.Set<UUID> leaving = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ScheduledTask> brains = new ConcurrentHashMap<>();
    private final Map<UUID, List<Companion>> hidden = new ConcurrentHashMap<>();
    private final Grudges grudges = new Grudges();
    private final java.util.Set<UUID> hidingOthers = ConcurrentHashMap.newKeySet();
    private final CompanionBrain brain;

    public CompanionService(Plugin core, Server server, Combat combat, Predicate<UUID> vanished) {
        this.core = core;
        this.server = server;
        this.combat = combat;
        this.vanished = vanished;
        this.brain = new CompanionBrain(this, combat, grudges, server);
    }

    // ------------------------------------------------------------------ asking

    public List<Companion> of(UUID owner) {
        return byId.values().stream().filter(companion -> companion.owner().equals(owner)).toList();
    }

    public List<Companion> all() {
        return List.copyOf(byId.values());
    }

    public Optional<Companion> byMob(UUID mob) {
        return mob == null ? Optional.empty() : Optional.ofNullable(byMob.get(mob));
    }

    public boolean isCompanion(UUID mob) {
        return mob != null && byMob.containsKey(mob);
    }

    /** Its health as a share of its most, 0 to 1; 0 when it is not in the world. */
    public double healthShare(Companion companion) {
        return mobOf(companion).map(mob -> mob.getHealth() / Math.max(1, maxHealth(mob))).orElse(0.0);
    }

    Optional<Mob> mobOf(Companion companion) {
        UUID mob = companion.mob();
        return mob != null && server.getEntity(mob) instanceof Mob made && made.isValid()
                ? Optional.of(made) : Optional.empty();
    }

    Grudges grudges() {
        return grudges;
    }

    // ------------------------------------------------------------------ summoning

    /** Summons a companion beside its owner. On the owner's thread. */
    public Summoned summon(Plugin plugin, Player owner, CompanionSpec spec, CompanionHooks hooks) {
        if (vanished.test(owner.getUniqueId())) {
            return new Summoned(null, Refusal.VANISHED);
        }
        if (spec.type().getEntityClass() == null || !Mob.class.isAssignableFrom(spec.type().getEntityClass())) {
            return new Summoned(null, Refusal.NOT_A_MOB);
        }
        Companion companion = new Companion(plugin, owner.getUniqueId(), spec, hooks);
        if (!place(companion, owner)) {
            return new Summoned(null, Refusal.FAILED);
        }
        byId.put(companion.id(), companion);
        return new Summoned(companion, null);
    }

    private boolean place(Companion companion, Player owner) {
        CompanionSpec spec = companion.spec();
        Location at = beside(owner);
        // Bound before it is added, so a claim's spawn and entry rules already see a companion, not a monster.
        Entity made = owner.getWorld().spawn(at, spec.type().getEntityClass(), CreatureSpawnEvent.SpawnReason.CUSTOM,
                entity -> {
                    combat.companions().bind(entity.getUniqueId(), companion.owner());
                    prepare(entity, spec);
                });
        if (!(made instanceof Mob mob) || !made.isValid()) {
            combat.companions().release(made.getUniqueId());
            made.remove();
            return false;
        }
        companion.mob(mob.getUniqueId());
        companion.gone = false;
        for (UUID viewer : hidingOthers) {
            if (!viewer.equals(companion.owner()) && server.getPlayer(viewer) instanceof Player hider) {
                shown(hider, companion, false);
            }
        }
        byMob.put(mob.getUniqueId(), companion);
        combat.companions().bind(mob.getUniqueId(), companion.owner());
        brains.put(companion.id(), Scheduling.entityTimer(core, mob, 1, 1, task -> brain.tick(companion, mob),
                () -> lost(companion, mob.getUniqueId())));
        return true;
    }

    private static Location beside(Player owner) {
        Location at = owner.getLocation().clone();
        at.add(at.getDirection().setY(0).normalize().multiply(-1.5));
        at.setY(owner.getLocation().getY() + 0.1);
        return at;
    }

    private static void prepare(Entity entity, CompanionSpec spec) {
        entity.setPersistent(false);
        if (entity instanceof Mob mob) {
            mob.setAware(false);
            mob.setRemoveWhenFarAway(false);
            mob.setCanPickupItems(false);
            mob.setCollidable(false);
            mob.setGravity(!spec.flies());
        }
        if (spec.baby()) {
            if (entity instanceof Ageable young) {
                young.setBaby();
            }
            if (entity instanceof Breedable locked) {
                locked.setAgeLock(true);
            }
        } else if (entity instanceof Ageable grown) {
            grown.setAdult();
        }
        if (entity instanceof Wither wither) {
            wither.setInvulnerableTicks(0);
        }
        if (entity instanceof EnderDragon dragon) {
            dragon.setPhase(EnderDragon.Phase.HOVER);
        }
        if (entity instanceof Boss boss && boss.getBossBar() != null) {
            boss.getBossBar().setVisible(false);
            boss.getBossBar().removeAll();
        }
        entity.setSilent(spec.silent());
        if (entity instanceof LivingEntity living) {
            AttributeInstance scale = living.getAttribute(Attribute.SCALE);
            if (scale != null) {
                scale.setBaseValue(spec.scaleFor(entity.getBoundingBox().getHeight()));
            }
            dress(living, spec);
            living.setHealth(maxHealth(living));
        }
    }

    private static void dress(LivingEntity living, CompanionSpec spec) {
        AttributeInstance health = living.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(spec.health());
        }
        living.customName(spec.name());
        living.setCustomNameVisible(true);
    }

    static double maxHealth(LivingEntity living) {
        AttributeInstance health = living.getAttribute(Attribute.MAX_HEALTH);
        return health == null ? 20 : health.getValue();
    }

    /** A changed spec — a level, a name — put on the mob, its share of health kept. */
    public void update(Companion companion, CompanionSpec spec) {
        companion.spec(spec);
        mobOf(companion).ifPresent(mob -> Scheduling.entity(core, mob, () -> {
            mob.setSilent(spec.silent());
            double share = mob.getHealth() / Math.max(1, maxHealth(mob));
            dress(mob, spec);
            mob.setHealth(Math.max(1, Math.min(maxHealth(mob), share * maxHealth(mob))));
        }));
    }

    /** Heals it by this much. */
    public void heal(Companion companion, double amount) {
        mobOf(companion).ifPresent(mob -> Scheduling.entity(core, mob,
                () -> mob.setHealth(Math.min(maxHealth(mob), mob.getHealth() + amount))));
    }

    // ------------------------------------------------------------------ who sees them

    /**
     * Whether this player sees other players' companions — off hides them, sound and all, for this player only (a
     * mob's sounds cannot be muted for one listener; hiding it can). Their own are always shown.
     */
    public void seeOthers(Player viewer, boolean see) {
        if (see) {
            hidingOthers.remove(viewer.getUniqueId());
        } else {
            hidingOthers.add(viewer.getUniqueId());
        }
        for (Companion companion : all()) {
            if (!companion.owner().equals(viewer.getUniqueId())) {
                shown(viewer, companion, see);
            }
        }
    }

    public boolean seesOthers(UUID viewer) {
        return !hidingOthers.contains(viewer);
    }

    private void shown(Player viewer, Companion companion, boolean see) {
        UUID id = companion.mob();
        if (id == null) {
            return;
        }
        Scheduling.entity(core, viewer, () -> {
            Entity entity = server.getEntity(id);
            if (entity == null) {
                return;
            }
            if (see) {
                viewer.showEntity(core, entity);
            } else {
                viewer.hideEntity(core, entity);
            }
        });
    }

    // ------------------------------------------------------------------ taking away

    /** Sends it home. Its plugin hears {@link CompanionHooks.Gone#DISMISSED}. */
    public void dismiss(Companion companion) {
        remove(companion, CompanionHooks.Gone.DISMISSED);
    }

    void remove(Companion companion, CompanionHooks.Gone why) {
        if (byId.remove(companion.id()) == null) {
            return;
        }
        takeMob(companion);
        companion.gone = true;
        tell(companion, why);
    }

    private void takeMob(Companion companion) {
        UUID mob = companion.mob();
        if (mob != null) {
            byMob.remove(mob);
            combat.companions().release(mob);
        }
        ScheduledTask task = brains.remove(companion.id());
        if (task != null) {
            task.cancel();
        }
        Entity entity = mob == null ? null : server.getEntity(mob);
        if (entity != null) {
            leaving.add(mob);
            Scheduling.entity(core, entity, () -> {
                entity.eject();
                entity.remove();
                leaving.remove(mob);
            });
        }
    }

    /** A companion's mob on its way out — beaten, sent home — which is still in the world this tick. */
    boolean isLeaving(UUID mob) {
        return mob != null && leaving.contains(mob);
    }

    private void tell(Companion companion, CompanionHooks.Gone why) {
        try {
            companion.hooks().gone(companion, why);
        } catch (RuntimeException broken) {
            core.getLogger().warning("A companion's plugin failed on " + why + ": " + broken);
        }
    }

    /** The mob went without being sent. */
    private void lost(Companion companion, UUID mob) {
        if (byMob.get(mob) == companion && byId.containsKey(companion.id())) {
            byMob.remove(mob);
            combat.companions().release(mob);
            brains.remove(companion.id());
            byId.remove(companion.id());
            companion.gone = true;
            tell(companion, CompanionHooks.Gone.LOST);
        }
    }

    /** Beaten: taken away, never killed. */
    void beaten(Companion companion) {
        remove(companion, CompanionHooks.Gone.BEATEN);
    }

    // ------------------------------------------------------------------ following the owner

    /** Its owner left: every companion goes. */
    public void ownerLeft(UUID owner) {
        of(owner).forEach(companion -> remove(companion, CompanionHooks.Gone.OWNER_LEFT));
        hidden.remove(owner);
        hidingOthers.remove(owner);
    }

    /** Its owner vanished: every companion goes, and comes back with them. */
    public void ownerVanished(UUID owner) {
        List<Companion> taken = of(owner);
        if (taken.isEmpty()) {
            return;
        }
        for (Companion companion : taken) {
            byId.remove(companion.id());
            takeMob(companion);
            companion.gone = true;
            tell(companion, CompanionHooks.Gone.OWNER_VANISHED);
        }
        hidden.put(owner, taken);
    }

    /** Its owner reappeared: what went with them comes back. On the owner's thread. */
    public void ownerRevealed(Player owner) {
        List<Companion> back = hidden.remove(owner.getUniqueId());
        if (back == null) {
            return;
        }
        for (Companion companion : back) {
            if (place(companion, owner)) {
                byId.put(companion.id(), companion);
            }
        }
    }

    /** Its owner changed worlds: a new mob there, or none where it may not go. On the owner's thread. */
    public void ownerChangedWorld(Player owner) {
        for (Companion companion : of(owner.getUniqueId())) {
            if (!companion.hooks().allowedIn(companion, owner.getWorld().getName())) {
                remove(companion, CompanionHooks.Gone.NOT_ALLOWED_HERE);
                continue;
            }
            takeMob(companion);
            place(companion, owner);
        }
    }

    /** A plugin stopped: everything it summoned goes. */
    public void forgetFrom(Plugin plugin) {
        byId.values().stream().filter(companion -> companion.plugin() == plugin).toList()
                .forEach(companion -> remove(companion, CompanionHooks.Gone.LOST));
        hidden.values().forEach(list -> list.removeIf(companion -> companion.plugin() == plugin));
    }

    /** Core stopping. */
    public void clear() {
        all().forEach(companion -> remove(companion, CompanionHooks.Gone.LOST));
        hidden.clear();
    }

    Plugin core() {
        return core;
    }

    CompanionBrain brain() {
        return brain;
    }
}
