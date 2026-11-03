package de.raindancer.core.world.activity;

import de.raindancer.core.world.blocks.PlacedBlocks;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.raid.RaidFinishEvent;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What players do, told once to every plugin that listens: ores, stone, dirt and logs the world made mined, dug and
 * chopped, grown crops harvested, berries, mushrooms and flowers foraged, fish caught, animals bred and tamed, trades,
 * crafting, smelting, cooking, brewing, building, anvil work, enchanting, advancements, biomes first found, raids,
 * monsters and bosses killed, and minutes played and blocks travelled while not away. One place decides what counts — never an ore a player placed, never a melon from a farm of placed
 * blocks, never a monster from a spawner or a spawn egg, never a breed of parents bought as eggs — so guild experience, quests and adventurer levels cannot be farmed three different ways.
 *
 * <p>Listeners are told on the thread of the event, usually the player's own; they should be quick.
 */
public final class Activities {

    private record Listening(Plugin owner, BiConsumer<Player, Activity> listener) {
    }

    private static final Logger LOG = Logger.getLogger("RainsCore");
    private static final List<Listening> listening = new CopyOnWriteArrayList<>();
    private static final Set<EntityType> BOSSES = Set.of(EntityType.WITHER, EntityType.ENDER_DRAGON,
            EntityType.ELDER_GUARDIAN, EntityType.WARDEN);
    /** Mobs a farm makes: a monster from these is not a fight. */
    private static final Set<CreatureSpawnEvent.SpawnReason> FARMED = Set.of(CreatureSpawnEvent.SpawnReason.SPAWNER,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG, CreatureSpawnEvent.SpawnReason.DISPENSE_EGG);
    /** Parents bought as eggs could be bred without end; nor do ones a command or a plugin made count. */
    private static final Set<CreatureSpawnEvent.SpawnReason> NOT_RAISED = Set.of(
            CreatureSpawnEvent.SpawnReason.SPAWNER, CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
            CreatureSpawnEvent.SpawnReason.DISPENSE_EGG, CreatureSpawnEvent.SpawnReason.COMMAND,
            CreatureSpawnEvent.SpawnReason.CUSTOM);

    private Activities() {
    }

    /** Starts telling {@code listener} what players do. @return what to hand to {@link #stop} */
    public static Object listen(Plugin owner, BiConsumer<Player, Activity> listener) {
        Listening entry = new Listening(owner, listener);
        listening.add(entry);
        return entry;
    }

    public static void stop(Object handle) {
        listening.remove(handle);
    }

    public static int listening() {
        return listening.size();
    }

    /** Tells every listener; one that throws is logged and the others are still told. */
    public static void tell(Player player, Activity activity) {
        if (activity.amount() <= 0) {
            return;
        }
        for (Listening each : listening) {
            try {
                each.listener().accept(player, activity);
            } catch (RuntimeException broken) {
                LOG.log(Level.WARNING, "A listener for player activities (" + (each.owner() == null ? "?"
                        : each.owner().getName()) + ") failed on " + activity + ".", broken);
            }
        }
    }

    private static final TravelMeter travel = new TravelMeter();

    /**
     * Once a minute for each player who is not away, on their own thread: the blocks they walked, sprinted, swum and
     * climbed since the last time. Core's minute timer calls this.
     */
    public static void minute(Player player) {
        long centimetres = 0;
        for (org.bukkit.Statistic statistic : List.of(org.bukkit.Statistic.WALK_ONE_CM,
                org.bukkit.Statistic.SPRINT_ONE_CM, org.bukkit.Statistic.SWIM_ONE_CM,
                org.bukkit.Statistic.WALK_UNDER_WATER_ONE_CM, org.bukkit.Statistic.CLIMB_ONE_CM)) {
            centimetres += player.getStatistic(statistic);
        }
        long blocks = travel.since(player.getUniqueId(), centimetres);
        tell(player, Activity.of(ActivityKind.TRAVEL, blocks));
    }

    public static int forgetFrom(ClassLoader loader) {
        int before = listening.size();
        listening.removeIf(each -> each.owner() != null && each.owner().getClass().getClassLoader() == loader);
        return before - listening.size();
    }

    public static void clear() {
        listening.clear();
    }

    /** Turns the game's events into activities. Registered once by Core, which also watches {@link Ores#watched()}. */
    public static final class Listener implements org.bukkit.event.Listener {

        static final RecentSpots spots = new RecentSpots(600_000L, 512);
        /** Who last worked each brewing stand: a brew has no player of its own. */
        private final Map<String, java.util.UUID> brewers = new java.util.concurrent.ConcurrentHashMap<>();

        /** A break's "was it placed?", read before PlacedBlocks takes the mark off at MONITOR. */
        private final Map<BlockBreakEvent, Boolean> placed = Collections.synchronizedMap(new WeakHashMap<>());

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void beforeBreak(BlockBreakEvent event) {
            Material type = event.getBlock().getType();
            if (Ores.oreOf(type).isPresent() || Ores.GROWN_BLOCKS.contains(type) || Terrain.kindOf(type).isPresent()) {
                placed.put(event, PlacedBlocks.isPlaced(event.getBlock()));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBreak(BlockBreakEvent event) {
            Block block = event.getBlock();
            Material type = block.getType();
            Boolean wasPlaced = placed.remove(event);
            var ore = Ores.oreOf(type);
            if (ore.isPresent()) {
                if (Boolean.FALSE.equals(wasPlaced)) {
                    tell(event.getPlayer(), Activity.of(ActivityKind.MINE, ore.get()));
                }
                return;
            }
            var terrain = Terrain.kindOf(type);
            if (terrain.isPresent()) {
                if (Boolean.FALSE.equals(wasPlaced)) {
                    tell(event.getPlayer(), Activity.of(terrain.get(), type.getKey().getKey()));
                }
                return;
            }
            if (Ores.AGED_CROPS.contains(type) && block.getBlockData() instanceof Ageable crop
                    && crop.getAge() >= crop.getMaximumAge()) {
                tell(event.getPlayer(), Activity.of(ActivityKind.HARVEST, type.getKey().getKey()));
            } else if (Ores.GROWN_BLOCKS.contains(type) && Boolean.FALSE.equals(wasPlaced)) {
                tell(event.getPlayer(), Activity.of(ActivityKind.HARVEST, type.getKey().getKey()));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onKill(EntityDeathEvent event) {
            LivingEntity dead = event.getEntity();
            Player killer = dead.getKiller();
            if (killer == null || dead instanceof Player || FARMED.contains(dead.getEntitySpawnReason())) {
                return;
            }
            String what = dead.getType().getKey().getKey();
            if (BOSSES.contains(dead.getType())) {
                tell(killer, Activity.of(ActivityKind.BOSS, what));
            } else if (dead instanceof Enemy) {
                tell(killer, Activity.of(ActivityKind.MONSTER, what));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onAdvancement(PlayerAdvancementDoneEvent event) {
            var display = event.getAdvancement().getDisplay();
            if (display != null && display.doesAnnounceToChat()) {
                tell(event.getPlayer(), Activity.of(ActivityKind.ADVANCEMENT, event.getAdvancement().getKey().getKey()));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onFish(PlayerFishEvent event) {
            if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
                String what = event.getCaught() instanceof Item caught
                        ? caught.getItemStack().getType().getKey().getKey() : "";
                tell(event.getPlayer(), Activity.of(ActivityKind.FISH, what));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBreed(EntityBreedEvent event) {
            if (NOT_RAISED.contains(event.getMother().getEntitySpawnReason())
                    || NOT_RAISED.contains(event.getFather().getEntitySpawnReason())) {
                return;
            }
            if (event.getBreeder() instanceof Player breeder) {
                tell(breeder, Activity.of(ActivityKind.BREED, event.getEntityType().getKey().getKey()));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onTrade(PlayerTradeEvent event) {
            tell(event.getPlayer(), Activity.of(ActivityKind.TRADE,
                    event.getTrade().getResult().getType().getKey().getKey()));
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onEnchant(EnchantItemEvent event) {
            tell(event.getEnchanter(), new Activity(ActivityKind.ENCHANT, String.valueOf(event.getExpLevelCost()),
                    event.whichButton() + 1));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onRaid(RaidFinishEvent event) {
            for (Player hero : event.getWinners()) {
                tell(hero, Activity.of(ActivityKind.RAID, 1));
            }
        }
    
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPick(org.bukkit.event.player.PlayerHarvestBlockEvent event) {
            int picked = event.getItemsHarvested().stream().mapToInt(org.bukkit.inventory.ItemStack::getAmount).sum();
            tell(event.getPlayer(), new Activity(ActivityKind.FORAGE,
                    event.getHarvestedBlock().getType().getKey().getKey(), Math.max(1, picked)));
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onCraft(org.bukkit.event.inventory.CraftItemEvent event) {
            if (!(event.getWhoClicked() instanceof Player crafter)) {
                return;
            }
            org.bukkit.inventory.ItemStack[] matrix = event.getInventory().getMatrix();
            List<Material> ingredients = new java.util.ArrayList<>();
            List<Integer> stacks = new java.util.ArrayList<>();
            for (org.bukkit.inventory.ItemStack each : matrix) {
                if (each != null && !each.isEmpty()) {
                    ingredients.add(each.getType());
                    stacks.add(each.getAmount());
                }
            }
            if (!Crafts.counts(ingredients)) {
                return;
            }
            org.bukkit.inventory.ItemStack result = event.getRecipe().getResult();
            int times = event.isShiftClick() ? Crafts.times(stacks) : 1;
            tell(crafter, new Activity(ActivityKind.CRAFT, result.getType().getKey().getKey(),
                    (long) times * result.getAmount()));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onTakeOut(org.bukkit.event.inventory.FurnaceExtractEvent event) {
            Material item = event.getItemType();
            tell(event.getPlayer(), new Activity(item.isEdible() ? ActivityKind.COOK : ActivityKind.SMELT,
                    item.getKey().getKey(), event.getItemAmount()));
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onStandClick(org.bukkit.event.inventory.InventoryClickEvent event) {
            if (event.getView().getTopInventory() instanceof org.bukkit.inventory.BrewerInventory stand
                    && stand.getLocation() != null && event.getWhoClicked() instanceof Player brewer) {
                brewers.put(spot(stand.getLocation()), brewer.getUniqueId());
            }
            if (event.getView() instanceof org.bukkit.inventory.view.AnvilView anvil && event.getRawSlot() == 2
                    && event.getCurrentItem() != null && !event.getCurrentItem().isEmpty()
                    && event.getWhoClicked() instanceof Player smith
                    && (smith.getGameMode() == org.bukkit.GameMode.CREATIVE || smith.getLevel() >= anvil.getRepairCost())) {
                tell(smith, new Activity(ActivityKind.REPAIR, event.getCurrentItem().getType().getKey().getKey(),
                        Math.max(1, anvil.getRepairCost())));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBrew(org.bukkit.event.inventory.BrewEvent event) {
            java.util.UUID who = brewers.get(spot(event.getBlock().getLocation()));
            Player brewer = who == null ? null : org.bukkit.Bukkit.getPlayer(who);
            if (brewer == null) {
                return;
            }
            int bottles = 0;
            for (int slot = 0; slot < 3; slot++) {
                org.bukkit.inventory.ItemStack bottle = event.getContents().getItem(slot);
                if (bottle != null && !bottle.isEmpty()) {
                    bottles++;
                }
            }
            org.bukkit.inventory.ItemStack ingredient = event.getContents().getIngredient();
            tell(brewer, new Activity(ActivityKind.BREW,
                    ingredient == null ? "" : ingredient.getType().getKey().getKey(), bottles));
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPlace(org.bukkit.event.block.BlockPlaceEvent event) {
            Block block = event.getBlockPlaced();
            if (spots.firstTime(event.getPlayer().getUniqueId(), block.getWorld().getName(), block.getX(), block.getY(),
                    block.getZ(), System.currentTimeMillis())) {
                tell(event.getPlayer(), Activity.of(ActivityKind.BUILD, block.getType().getKey().getKey()));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onTame(org.bukkit.event.entity.EntityTameEvent event) {
            if (event.getOwner() instanceof Player owner) {
                tell(owner, Activity.of(ActivityKind.TAME, event.getEntityType().getKey().getKey()));
            }
        }

        /** A biome first entered: the game's own Adventuring Time remembers it, so it counts once a player, ever. */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onCriterion(com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent event) {
            if (event.getAdvancement().getKey().getKey().equals("adventure/adventuring_time")) {
                tell(event.getPlayer(), Activity.of(ActivityKind.EXPLORE, event.getCriterion()));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
            java.util.UUID id = event.getPlayer().getUniqueId();
            spots.forget(id);
            travel.forget(id);
        }

        private static String spot(org.bukkit.Location at) {
            return at.getWorld().getName() + ':' + at.getBlockX() + ':' + at.getBlockY() + ':' + at.getBlockZ();
        }
    }
}
