package de.raindancer.core.world.protection;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Enforces {@link LockedWorlds}: in a locked world nothing is changed or taken except by somebody building.
 *
 * <ul>
 *   <li>Blocks: no breaking, placing, buckets, trampling, signs; no right-click use of a block (doors, levers, beds,
 *       crafting tables), no containers, no ender chest — opened any way at all.</li>
 *   <li>Items: only harmless ones used against a block ({@link LockedWorldRules#usableAgainstBlocks}); no drops, no
 *       pickups.</li>
 *   <li>Entities: frames, paintings, stands and vehicles neither placed nor broken nor used; no shearing, leashing, fish
 *       buckets; no wither, no ender dragon, /summon included.</li>
 *   <li>The world itself: explosions break no block, mobs and falling blocks change none, no fire, burning, spreading,
 *       fading, forming, growing, decaying; an arrow breaks no pot and lights no TNT.</li>
 * </ul>
 *
 * <p>LOW, so a plugin that wants to allow something in its own locked world on purpose can still un-cancel it later.
 */
public final class LockedWorldListener implements Listener {

    private static boolean locked(World world) {
        return world != null && LockedWorlds.isLocked(world.getName());
    }

    private static boolean building(Player player) {
        return LockedWorlds.isBuilding(player.getWorld().getName(), player);
    }

    /** Cancels when the player is in a locked world and not building there. */
    private static void judge(Player player, Cancellable event) {
        if (player != null && locked(player.getWorld()) && !building(player)) {
            event.setCancelled(true);
        }
    }

    private static void cancelIn(World world, Cancellable event) {
        if (locked(world)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        judge(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        judge(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        judge(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        judge(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        judge(event.getPlayer(), event);
    }

    /** Not ignoreCancelled: Bukkit hands right clicks on air in already cancelled, and those still use an item. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        if (block == null || !locked(block.getWorld()) || building(player)) {
            return;
        }
        if (event.getAction() == Action.PHYSICAL) {
            if (block.getType() == Material.FARMLAND || block.getType() == Material.TURTLE_EGG) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (block.getType().isInteractable() || block.getState(false) instanceof Container
                || block.getType() == Material.ENDER_CHEST) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
        ItemStack held = event.getItem();
        if (!LockedWorldRules.usableAgainstBlocks(held == null ? "AIR" : held.getType().name())) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    /** Whatever opens a container some other way — a command, a plugin, a minecart — is caught here. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        InventoryType type = event.getInventory().getType();
        boolean container = type == InventoryType.ENDER_CHEST
                || (type != InventoryType.CRAFTING && type != InventoryType.PLAYER
                && event.getInventory().getHolder() != null
                && !(event.getInventory().getHolder() instanceof Player)
                && !(event.getInventory().getHolder() instanceof de.raindancer.core.ui.menu.Menu));
        if (container) {
            judge(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        judge(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickUp(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            judge(player, event);
        } else {
            cancelIn(event.getEntity().getWorld(), event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityUse(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (clicked instanceof Hanging || clicked instanceof ArmorStand) {
            judge(event.getPlayer(), event);
        }
    }

    /** Clicking an armour stand's slot arrives as this, not as the plain entity click. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityUseAt(PlayerInteractAtEntityEvent event) {
        onEntityUse(event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmourStand(PlayerArmorStandManipulateEvent event) {
        judge(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        judge(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        judge(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        judge(event.getPlayer(), event);
    }

    /** Boats, minecarts, armour stands, end crystals — things an item places that are entities, not blocks. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() != null) {
            judge(event.getPlayer(), event);
        } else {
            cancelIn(event.getEntity().getWorld(), event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null) {
            judge(event.getPlayer(), event);
        } else {
            cancelIn(event.getEntity().getWorld(), event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player) {
            judge(player, event);
        } else {
            cancelIn(event.getEntity().getWorld(), event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleBreak(VehicleDestroyEvent event) {
        if (event.getAttacker() instanceof Player player) {
            judge(player, event);
        } else {
            cancelIn(event.getVehicle().getWorld(), event);
        }
    }

    /** Hitting a frame or a stand is breaking it. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHitThing(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Hanging) && !(event.getEntity() instanceof ArmorStand)) {
            return;
        }
        if (event.getDamager() instanceof Player player) {
            judge(player, event);
        } else {
            cancelIn(event.getEntity().getWorld(), event);
        }
    }

    /** A wither or a dragon is not let in, however it was made — /summon included. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (locked(event.getLocation().getWorld()) && !LockedWorldRules.mayExist(event.getEntityType().name())) {
            event.setCancelled(true);
        }
    }

    // ── the world itself ───────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (locked(event.getLocation().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (locked(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }

    /** Endermen, falling blocks, a mob trampling crops, a ravager breaking leaves — and a player only when building. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!locked(event.getBlock().getWorld())) {
            return;
        }
        if (!(event.getEntity() instanceof Player player) || !building(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(EntityInteractEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND || event.getBlock().getType() == Material.TURTLE_EGG) {
            cancelIn(event.getBlock().getWorld(), event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (locked(event.getBlock().getWorld()) && !(event.getPlayer() != null && building(event.getPlayer()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        cancelIn(event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        cancelIn(event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        cancelIn(event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        cancelIn(event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        cancelIn(event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        cancelIn(event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        cancelIn(event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        cancelIn(event.getWorld(), event);
    }

    /** An arrow breaking a pot, lighting TNT or snapping dripstone — the hit stands, the block does not change. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Block hit = event.getHitBlock();
        if (hit != null && locked(hit.getWorld()) && LockedWorldRules.fragile(hit.getType().name())) {
            event.setCancelled(true);
        }
    }
}
