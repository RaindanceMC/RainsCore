package de.raindancer.core.world.companion;

import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.entity.Boss;
import org.bukkit.entity.ElderGuardian;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityEnterLoveModeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Keeps companions out of trouble and out of other people's hands, and remembers their owners' fights. */
public final class CompanionListener implements Listener {

    /** What the world does that a companion never suffers. */
    private static final Set<EntityDamageEvent.DamageCause> SHRUGGED_OFF = EnumSet.of(
            EntityDamageEvent.DamageCause.FALL, EntityDamageEvent.DamageCause.SUFFOCATION,
            EntityDamageEvent.DamageCause.DROWNING, EntityDamageEvent.DamageCause.DRYOUT,
            EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.LAVA, EntityDamageEvent.DamageCause.HOT_FLOOR,
            EntityDamageEvent.DamageCause.CONTACT, EntityDamageEvent.DamageCause.CRAMMING,
            EntityDamageEvent.DamageCause.FREEZE, EntityDamageEvent.DamageCause.FLY_INTO_WALL,
            EntityDamageEvent.DamageCause.MELTING, EntityDamageEvent.DamageCause.STARVATION);
    private static final Set<CreatureSpawnEvent.SpawnReason> FARMED = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.SPAWNER, CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
            CreatureSpawnEvent.SpawnReason.DISPENSE_EGG, CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER);

    private final CompanionService companions;

    public CompanionListener(CompanionService companions) {
        this.companions = companions;
    }

    private Optional<Companion> of(Entity entity) {
        return entity == null ? Optional.empty() : companions.byMob(entity.getUniqueId());
    }

    /** Whoever is behind a hit: the shooter of a projectile, the owner of a companion. */
    private UUID behind(Entity damager) {
        Entity source = damager;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            source = shooter;
        }
        Optional<Companion> companion = of(source);
        return companion.map(Companion::owner).orElse(source == null ? null : source.getUniqueId());
    }

    // ------------------------------------------------------------------ hurting

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFriendlyFire(EntityDamageByEntityEvent event) {
        UUID by = behind(event.getDamager());
        Optional<Companion> victim = of(event.getEntity());
        if (victim.isPresent() && victim.get().owner().equals(by)) {
            event.setCancelled(true);
            return;
        }
        Optional<Companion> attacker = of(event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter ? shooter : event.getDamager());
        if (attacker.isPresent() && event.getEntity().getUniqueId().equals(attacker.get().owner())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        Optional<Companion> companion = of(event.getEntity());
        if (companion.isEmpty() && companions.isLeaving(event.getEntity().getUniqueId())) {
            // beaten by one hit, a second in the same tick would kill it before it is gone
            event.setCancelled(true);
            return;
        }
        if (companion.isEmpty() || !(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        if (SHRUGGED_OFF.contains(event.getCause())) {
            event.setCancelled(true);
            return;
        }
        companion.get().lastHurt = companion.get().ticks;
        if (event.getFinalDamage() >= living.getHealth()) {
            event.setCancelled(true);
            companions.beaten(companion.get());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFight(EntityDamageByEntityEvent event) {
        long now = System.currentTimeMillis();
        UUID by = behind(event.getDamager());
        if (event.getEntity() instanceof Player owner && by != null && !by.equals(owner.getUniqueId())
                && !companions.of(owner.getUniqueId()).isEmpty()) {
            Entity source = event.getDamager() instanceof Projectile projectile
                    && projectile.getShooter() instanceof Entity shooter ? shooter : event.getDamager();
            companions.grudges().hurt(owner.getUniqueId(), source.getUniqueId(), now);
        }
        Entity hitter = event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter ? shooter : event.getDamager();
        if (hitter instanceof Player owner && !companions.of(owner.getUniqueId()).isEmpty()
                && of(event.getEntity()).map(victim -> !victim.owner().equals(owner.getUniqueId())).orElse(true)) {
            companions.grudges().struck(owner.getUniqueId(), event.getEntity().getUniqueId(), now);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        if (of(event.getEntity()).isPresent() || companions.isLeaving(event.getEntity().getUniqueId())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            return;
        }
        Entity cause = event.getDamageSource().getCausingEntity();
        Optional<Companion> killer = of(cause);
        if (killer.isEmpty()) {
            return;
        }
        LivingEntity victim = event.getEntity();
        boolean boss = victim instanceof Boss || victim instanceof ElderGuardian || victim instanceof Warden;
        try {
            killer.get().hooks().killed(killer.get(), victim, boss, FARMED.contains(victim.getEntitySpawnReason()));
        } catch (RuntimeException broken) {
            companions.core().getLogger().warning("A companion's plugin failed on a kill: " + broken);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMissile(ProjectileHitEvent event) {
        companions.brain().missile(event.getEntity().getUniqueId()).ifPresent(companion -> {
            event.setCancelled(true);
            companions.brain().hit(companion, event.getEntity(), event.getHitEntity());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (of(event.getEntity()).isPresent() || companions.brain().isMissile(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        if (of(event.getEntity()).isPresent() || companions.brain().isMissile(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(EntityCombustEvent event) {
        if (of(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ nobody else's

    @EventHandler(ignoreCancelled = true)
    public void onGrief(EntityChangeBlockEvent event) {
        if (of(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (of(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(EntityDropItemEvent event) {
        if (of(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (of(event.getMother()).isPresent() || of(event.getFather()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLove(EntityEnterLoveModeEvent event) {
        if (of(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (of(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (of(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(EntityPortalEvent event) {
        if (of(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        Optional<Companion> companion = of(event.getMount());
        if (companion.isPresent() && !event.getEntity().getUniqueId().equals(companion.get().owner())) {
            event.setCancelled(true);
        }
    }

    /** A monster going for somebody whose guard is close goes for the guard instead. */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (of(event.getEntity()).isPresent()) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getTarget() instanceof Player owner) || !(event.getEntity() instanceof Mob)) {
            return;
        }
        for (Companion companion : companions.of(owner.getUniqueId())) {
            if (companion.spec().fighting() != Fighting.GUARD) {
                continue;
            }
            companions.mobOf(companion).filter(guard -> guard.getWorld() == owner.getWorld()
                            && guard.getLocation().distanceSquared(event.getEntity().getLocation()) < 144)
                    .ifPresent(event::setTarget);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(PlayerInteractEntityEvent event) {
        Optional<Companion> found = of(event.getRightClicked());
        if (found.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Companion companion = found.get();
        Player player = event.getPlayer();
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!player.getUniqueId().equals(companion.owner())) {
            companion.hooks().inspected(companion, player);
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!held.isEmpty() && held.getType().isEdible() && companion.hooks().fed(companion, player, held)) {
            held.subtract();
            return;
        }
        if (companion.hooks().clicked(companion, player, player.isSneaking())) {
            return;
        }
        if (companion.spec().rideable() && !player.isSneaking()) {
            event.getRightClicked().addPassenger(player);
        }
    }

    // ------------------------------------------------------------------ the owner

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID who = event.getPlayer().getUniqueId();
        companions.ownerLeft(who);
        Grudges grudges = companions.grudges();
        grudges.forget(who);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        Player owner = event.getPlayer();
        if (!companions.of(owner.getUniqueId()).isEmpty()) {
            Scheduling.entityLater(companions.core(), owner, 5, () -> companions.ownerChangedWorld(owner));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginStop(PluginDisableEvent event) {
        companions.forgetFrom(event.getPlugin());
    }
}
