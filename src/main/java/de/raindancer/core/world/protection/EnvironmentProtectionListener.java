package de.raindancer.core.world.protection;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Turtle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Enforces the environmental flags: explosions, fire, decay, grief, pistons, fluids, weather and the
 * player comfort flags (fall damage, hunger, keep inventory, equipment wear).
 * <p>
 * Every handler exits early when the flag's policy is {@code DISABLED}, so an admin who removed a flag
 * really does get vanilla behaviour with no overhead.
 */
public final class EnvironmentProtectionListener implements Listener {


    private final Land land;

    /**
     * Somebody the movement listener took out of the air, if there is one to ask.
     *
     * <p>Optional so the two listeners are not a cycle: Core builds this one first. Null simply means nobody is
     * being caught, which is how it behaved before the grace existed.
     */
    private MovementProtectionListener grounding;

    public EnvironmentProtectionListener(Land land) {
        this.land = land;
    }

    /** Told about the listener that grounds gliders, so the fall it caused can be forgiven. */
    public void grounding(MovementProtectionListener grounding) {
        this.grounding = grounding;
    }

    /**
     * Whether the flag forbids this at that block.
     * <p>
     * Asks the land service rather than looking the claim up directly: the block may be on a town's open
     * street, where there is no claim at all but the town still has rules. A listener that gave up on
     * "no claim here" would walk straight past exactly the land a town exists to govern.
     */
    private boolean denied(Location location, LandFlag flag) {
        if (!land.landFlags().isEnforced(flag)) {
            return false;
        }
        return !land.landFlags().isAllowedAt(location, flag);
    }

    /**
     * The same question as {@link #denied}, but about a creature rather than a block.
     * <p>
     * A player's claim is resolved the way the border tracker resolves it, tolerance included. Asking
     * for their exact block instead means somebody standing on their own roof — inside the claim for
     * every other purpose — loses the protection the flag promises them.
     * <p>
     * The victim's standing in the claim picks the value: an owner who keeps fall damage on for visitors
     * but off for themselves gets exactly that.
     */
    private boolean deniedFor(Entity victim, LandFlag flag) {
        if (!land.landFlags().isEnforced(flag)) {
            return false;
        }
        if (victim instanceof Player player) {
            // The border tracker's answer, tolerance included, so somebody on their own roof keeps the
            // protection the flag promises them.
            ProtectedArea tracked = land.areaAround(player).orElse(null);
            return !land.landFlags().isAllowedForTracked(tracked, player.getLocation(), flag, player);
        }
        return !land.landFlags().isAllowedFor(victim, flag);
    }

    // ------------------------------------------------------------ explosions

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!land.landFlags().isEnforced(LandFlag.EXPLOSIONS)) {
            return;
        }
        // Remove only the blocks inside protected claims instead of cancelling the whole blast, so an
        // explosion outside a claim still works normally right up to the border.
        Player lit = litBy(event.getEntity());
        event.blockList().removeIf(block -> denied(block.getLocation(), LandFlag.EXPLOSIONS)
                && !openedTo(lit, block.getLocation()));
    }

    /** Whoever lit this TNT, if they are still here — a raid's charge is theirs. */
    static Player litBy(org.bukkit.entity.Entity exploded) {
        if (exploded instanceof org.bukkit.entity.TNTPrimed tnt && tnt.getSource() instanceof Player player
                && player.isOnline()) {
            return player;
        }
        return null;
    }

    /** Whether a rule besides the claim (a raid) lets this player break blocks there, explosion flag or not. */
    private boolean openedTo(Player player, org.bukkit.Location where) {
        if (player == null) {
            return false;
        }
        return land.areaAt(where).map(area -> land.allowedByOthers(player, area, LandAction.BREAK)).orElse(false);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!land.landFlags().isEnforced(LandFlag.EXPLOSIONS)) {
            return;
        }
        event.blockList().removeIf(block -> denied(block.getLocation(), LandFlag.EXPLOSIONS));
    }

    // ------------------------------------------------------------ fire

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (denied(event.getBlock().getLocation(), LandFlag.FIRE_SPREAD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (event.getSource().getType() != Material.FIRE && event.getSource().getType() != Material.SOUL_FIRE) {
            return;
        }
        if (denied(event.getBlock().getLocation(), LandFlag.FIRE_SPREAD)) {
            event.setCancelled(true);
        }
    }

    /** Lightning, lava and fire spread ignitions have no player behind them. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() != null) {
            return;
        }
        if (denied(event.getBlock().getLocation(), LandFlag.FIRE_SPREAD)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ decay, weather

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeafDecay(LeavesDecayEvent event) {
        if (denied(event.getBlock().getLocation(), LandFlag.LEAF_DECAY)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        Material type = event.getNewState().getType();
        if (type != Material.SNOW && type != Material.ICE && type != Material.FROSTED_ICE) {
            return;
        }
        if (denied(event.getBlock().getLocation(), LandFlag.SNOW_ICE_FORM)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        Material type = event.getBlock().getType();
        if (type != Material.SNOW && type != Material.ICE && type != Material.FROSTED_ICE) {
            return;
        }
        if (denied(event.getBlock().getLocation(), LandFlag.SNOW_ICE_FORM)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ mob grief

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        LandFlag flag = griefFlagFor(event.getEntity());
        if (flag == null) {
            return;
        }
        if (denied(event.getBlock().getLocation(), flag)) {
            event.setCancelled(true);
        }
    }

    /**
     * Which grief flag governs this entity changing a block, or {@code null} when none does.
     * <p>
     * Sand, gravel, anvils and concrete powder become a {@link FallingBlock} entity, and both leaving the
     * old spot and settling into the new one arrive as an {@link EntityChangeBlockEvent}. That is plain
     * gravity rather than a mob chewing on somebody's build, so no flag applies — treating it as grief
     * left falling blocks frozen in place inside every claim that had mob grief switched off.
     * <p>
     * A turtle laying its egg fires the same event on the same entity type this method otherwise treats as a
     * predator, so it needs its own exemption: without one, a claim owner who leaves mob grief off (the
     * sensible default, since it also covers zombies breaking doors) can never breed turtles on their own
     * claim — the turtle digs the nest animation and then the egg placement is silently cancelled.
     */
    static LandFlag griefFlagFor(Entity entity) {
        if (entity instanceof Player || entity instanceof FallingBlock || entity instanceof Turtle) {
            return null;
        }
        return entity instanceof Enderman ? LandFlag.ENDERMAN_GRIEF : LandFlag.MOB_GRIEF;
    }

    // ------------------------------------------------------------ pistons and fluids

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (pistonCrossesBorder(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pistonCrossesBorder(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    /**
     * True when a piston outside protected ground reaches into it.
     *
     * <p>Both the blocks being moved <em>and where they end up</em>. Checking only the moved blocks left the
     * obvious griefing machine working: put the piston outside, put an unclaimed block next to the border, and
     * push it in. Every block in the list was unclaimed, so nothing objected, and the block landed inside —
     * replacing whatever was there.
     */
    private boolean pistonCrossesBorder(Block piston, List<Block> moved,
                                        BlockFace direction) {
        if (!land.landFlags().isEnforced(LandFlag.PISTONS_FROM_OUTSIDE)) {
            return false;
        }
        Optional<ProtectedArea> pistonArea = land.areaAt(piston.getLocation());
        for (Block block : moved) {
            if (reachesInto(pistonArea, block)) {
                return true;
            }
            if (direction != null && reachesInto(pistonArea, block.getRelative(direction))) {
                return true;
            }
        }
        return false;
    }

    /** Whether this block is on protected ground the piston is not itself standing on. */
    private boolean reachesInto(Optional<ProtectedArea> pistonArea, Block block) {
        Optional<ProtectedArea> area = land.areaAt(block.getLocation());
        if (area.isEmpty()) {
            return false;
        }
        boolean sameGround = pistonArea.isPresent() && pistonArea.get().id().equals(area.get().id());
        return !sameGround && !land.flags().isAllowed(area.get(), LandFlag.PISTONS_FROM_OUTSIDE);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        // Two questions, asked in this order. "May it flow here at all" is area wide and cheap; "may it come
        // in from outside" needs both ends looked up, and there is no point asking it about a flow that is
        // already refused.
        if (land.landFlags().isEnforced(LandFlag.FLUID_FLOW)
                && !land.landFlags().isAllowedAt(event.getToBlock().getLocation(), LandFlag.FLUID_FLOW)) {
            event.setCancelled(true);
            return;
        }
        if (!land.landFlags().isEnforced(LandFlag.FLUIDS_FROM_OUTSIDE)) {
            return;
        }
        Optional<ProtectedArea> target = land.areaAt(event.getToBlock().getLocation());
        if (target.isEmpty()) {
            return;
        }
        Optional<ProtectedArea> source = land.areaAt(event.getBlock().getLocation());
        boolean sameClaim = source.isPresent() && source.get().id().equals(target.get().id());
        if (!sameClaim && !land.flags().isAllowed(target.get(), LandFlag.FLUIDS_FROM_OUTSIDE)) {
            event.setCancelled(true);
        }
    }

    /**
     * What happens to somebody's things when they die here.
     *
     * <p>Three outcomes rather than vanilla's two, which is the whole reason these are two flags:
     *
     * <ul>
     *   <li><b>Keep inventory</b> — they get up with everything. Beats the other flag; somebody who keeps their
     *       things has nothing to drop.</li>
     *   <li><b>Items drop</b> — vanilla. The pile appears where they fell.</li>
     *   <li><b>Neither</b> — the things are gone. An arena that hands out its own kit wants this: vanilla fills
     *       the floor with other people's armour, and keep-inventory removes the stake entirely.</li>
     * </ul>
     *
     * <p>Experience follows the items. Keeping your sword and losing thirty levels is a rule nobody asked for,
     * and dropping experience where the items vanished leaves a glowing pile marking a death that cost nothing.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Location where = player.getLocation();

        // isAppliedAt, not isAllowedAt. This flag's true means "keep their things", not "keeping their
        // things is permitted", and the permission question answers yes on unclaimed ground — which is most
        // of a world. Asked the wrong way round, this switched keep-inventory on for the entire server and
        // nothing an owner could configure turned it off again.
        if (land.landFlags().isEnforced(LandFlag.KEEP_INVENTORY)
                && land.landFlags().isAppliedAt(where, LandFlag.KEEP_INVENTORY,
                        player.getUniqueId())) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
            return;
        }

        if (land.landFlags().isEnforced(LandFlag.ITEM_DROPS)
                && !land.landFlags().isAllowedAt(where, LandFlag.ITEM_DROPS, player.getUniqueId())) {
            // Not kept and not dropped: gone. Cleared rather than kept, which is the difference between this
            // and the flag above.
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    /**
     * A totem saving somebody.
     *
     * <p>Cancelling the resurrection is not a thing Bukkit offers directly — the totem fires as an
     * {@code EntityResurrectEvent}, which <em>is</em> cancellable, and cancelling it means the totem is not
     * consumed and the death goes ahead. Which is the right behaviour: they keep the item and lose the fight,
     * rather than losing an expensive item to a rule they did not know about.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTotem(EntityResurrectEvent event) {
        if (!land.landFlags().isEnforced(LandFlag.TOTEMS)) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;   // a totem in a mob's hand is not what this is about
        }
        if (land.isBypassing(player)) {
            return;
        }
        if (!land.landFlags().isAllowedAt(player.getLocation(), LandFlag.TOTEMS, player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Whether redstone runs.
     *
     * <p>Not about who may place it — that is the BUILD action — but whether what is placed does anything. Off
     * freezes the machines where they stand: no pistons, no dispensers, no doors opening themselves.
     *
     * <p>Area wide rather than per person, because a circuit cannot run for the owner and not for a visitor. It
     * is one machine.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        if (!land.landFlags().isEnforced(LandFlag.REDSTONE)) {
            return;
        }
        if (land.landFlags().isAllowedAt(event.getBlock().getLocation(), LandFlag.REDSTONE)) {
            return;
        }
        // Held at its old level rather than cancelled: this event has no cancel, and putting the new current
        // back to the old one is what "nothing changed" means to everything downstream of it.
        event.setNewCurrent(event.getOldCurrent());
    }

    /**
     * Breeding animals.
     *
     * <p>The flag people reach for after their first lag report: two players, forty cows, one chunk. Separate
     * from ANIMAL_SPAWNING, which is the world putting animals there — this is somebody standing in a pen with
     * a bucket of wheat.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!land.landFlags().isEnforced(LandFlag.BREEDING)) {
            return;
        }
        LivingEntity breeder = event.getBreeder();
        UUID who = breeder instanceof Player person ? person.getUniqueId() : null;
        if (breeder instanceof Player person && land.isBypassing(person)) {
            return;
        }
        if (!land.landFlags().isAllowedAt(event.getEntity().getLocation(), LandFlag.BREEDING, who)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ visitor comfort

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();

        // Explosion damage to creatures is deliberately separate from EXPLOSIONS, which only governs
        // block damage: a claim may want fireworks and TNT mining without visitors getting blown up.
        if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            // Every entity, not only the living ones. Item frames, paintings, minecarts and boats are
            // exactly what somebody detonating TNT at a border is after, and they are not LivingEntity —
            // so the flag protected the cows and left the map wall on the floor.
            if (deniedFor(event.getEntity(), LandFlag.EXPLOSION_DAMAGE)) {
                event.setCancelled(true);
            }
            return;
        }

        if (cause != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        // A fall the plugin caused by grounding a glider at a border. Forgiven whatever the flag says: the
        // player did not choose to drop, and charging them for it turns "no elytra flight here" into a border
        // that kills anybody who flies into it.
        if (grounding != null && grounding.wasCaughtFalling(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (deniedFor(player, LandFlag.FALL_DAMAGE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        // Only block food loss; eating must always work.
        if (event.getFoodLevel() >= player.getFoodLevel()) {
            return;
        }
        if (deniedFor(player, LandFlag.HUNGER)) {
            event.setCancelled(true);
        }
    }

    /**
     * Worn or held, whatever it is — see the flag's own note on why this does not split the two.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEquipmentWear(PlayerItemDamageEvent event) {
        if (deniedFor(event.getPlayer(), LandFlag.ARMOR_DURABILITY)) {
            event.setCancelled(true);
        }
    }

}
