package de.raindancer.core.world.activity;

import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Material;
import org.bukkit.entity.Cow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActivitiesTest {

    @AfterEach
    void clear() {
        Activities.clear();
    }

    @Test
    @DisplayName("an activity keeps its what in lower case and never a negative amount")
    void activity() {
        Activity mined = Activity.of(ActivityKind.MINE, "Iron");
        assertThat(mined.what()).isEqualTo("iron");
        assertThat(mined.amount()).isEqualTo(1);
        assertThat(new Activity(ActivityKind.FISH, null, -4).amount()).isZero();
        assertThat(new Activity(ActivityKind.FISH, null, -4).what()).isEmpty();
    }

    @Test
    @DisplayName("ores come in groups with a rarity weight; both iron ores are iron; crops and grown blocks are known")
    void ores() {
        assertThat(Ores.oreOf(Material.IRON_ORE)).contains("iron");
        assertThat(Ores.oreOf(Material.DEEPSLATE_IRON_ORE)).contains("iron");
        assertThat(Ores.oreOf(Material.ANCIENT_DEBRIS)).contains("ancient-debris");
        assertThat(Ores.oreOf(Material.STONE)).isEmpty();
        assertThat(Ores.weight("diamond")).isGreaterThan(Ores.weight("iron"));
        assertThat(Ores.weight("nothing")).isEqualTo(1);
        assertThat(Ores.AGED_CROPS).contains(Material.WHEAT).doesNotContain(Material.MELON);
        assertThat(Ores.GROWN_BLOCKS).contains(Material.MELON, Material.PUMPKIN);
        assertThat(Ores.watched()).contains(Material.IRON_ORE, Material.MELON);
    }

    @Test
    @DisplayName("every listener hears every activity until it stops; one that throws does not stop the others")
    void listening() {
        List<String> heard = new ArrayList<>();
        Object first = Activities.listen(null, (player, activity) -> heard.add("a:" + activity.what()));
        Activities.listen(null, (player, activity) -> {
            throw new IllegalStateException("broken");
        });
        Activities.listen(null, (player, activity) -> heard.add("c:" + activity.what()));
        Activities.tell(null, Activity.of(ActivityKind.HARVEST, "wheat"));
        assertThat(heard).containsExactly("a:wheat", "c:wheat");
        Activities.stop(first);
        Activities.tell(null, Activity.of(ActivityKind.HARVEST, "carrots"));
        assertThat(heard).containsExactly("a:wheat", "c:wheat", "c:carrots");
        assertThat(Activities.listening()).isEqualTo(2);
    }

    @Test
    @DisplayName("nothing is told for an amount of nothing")
    void nothing() {
        List<Activity> heard = new ArrayList<>();
        Activities.listen(null, (player, activity) -> heard.add(activity));
        Activities.tell(null, new Activity(ActivityKind.ENCHANT, "", 0));
        assertThat(heard).isEmpty();
    }

    private final Player player = mock(Player.class);
    private final Activities.Listener listener = new Activities.Listener();

    private List<Activity> heard() {
        List<Activity> heard = new ArrayList<>();
        Activities.listen(null, (who, activity) -> heard.add(activity));
        return heard;
    }

    private static ItemStack stack(Material type) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(type);
        return stack;
    }

    @Test
    @DisplayName("a catch names what was reeled in")
    void fish() {
        List<Activity> heard = heard();
        PlayerFishEvent event = mock(PlayerFishEvent.class);
        Item caught = mock(Item.class);
        ItemStack cod = stack(Material.COD);
        when(caught.getItemStack()).thenReturn(cod);
        when(event.getState()).thenReturn(PlayerFishEvent.State.CAUGHT_FISH);
        when(event.getPlayer()).thenReturn(player);
        when(event.getCaught()).thenReturn(caught);
        listener.onFish(event);
        assertThat(heard).containsExactly(new Activity(ActivityKind.FISH, "cod", 1));
    }

    @Test
    @DisplayName("a trade names what was traded for")
    void trade() {
        List<Activity> heard = heard();
        PlayerTradeEvent event = mock(PlayerTradeEvent.class);
        MerchantRecipe recipe = mock(MerchantRecipe.class);
        ItemStack emerald = stack(Material.EMERALD);
        when(recipe.getResult()).thenReturn(emerald);
        when(event.getTrade()).thenReturn(recipe);
        when(event.getPlayer()).thenReturn(player);
        listener.onTrade(event);
        assertThat(heard).containsExactly(new Activity(ActivityKind.TRADE, "emerald", 1));
    }

    @Test
    @DisplayName("enchanting tells the levels spent, and what the enchantment cost")
    void enchant() {
        List<Activity> heard = heard();
        EnchantItemEvent event = mock(EnchantItemEvent.class);
        when(event.getEnchanter()).thenReturn(player);
        when(event.whichButton()).thenReturn(2);
        when(event.getExpLevelCost()).thenReturn(30);
        listener.onEnchant(event);
        assertThat(heard).containsExactly(new Activity(ActivityKind.ENCHANT, "30", 3));
    }

    private EntityDeathEvent zombieKilled(SpawnReason born) {
        Zombie zombie = mock(Zombie.class);
        when(zombie.getKiller()).thenReturn(player);
        when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
        when(zombie.getEntitySpawnReason()).thenReturn(born);
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(zombie);
        return event;
    }

    @Test
    @DisplayName("a monster from a spawner or a spawn egg is a farm, not a fight")
    void farmedMonsters() {
        List<Activity> heard = heard();
        listener.onKill(zombieKilled(SpawnReason.SPAWNER));
        listener.onKill(zombieKilled(SpawnReason.SPAWNER_EGG));
        listener.onKill(zombieKilled(SpawnReason.DISPENSE_EGG));
        assertThat(heard).isEmpty();
        listener.onKill(zombieKilled(SpawnReason.NATURAL));
        assertThat(heard).containsExactly(Activity.of(ActivityKind.MONSTER, "zombie"));
    }

    private EntityBreedEvent bred(SpawnReason mother, SpawnReason father) {
        EntityBreedEvent event = mock(EntityBreedEvent.class);
        Cow mum = mock(Cow.class);
        Cow dad = mock(Cow.class);
        when(mum.getEntitySpawnReason()).thenReturn(mother);
        when(dad.getEntitySpawnReason()).thenReturn(father);
        when(event.getMother()).thenReturn(mum);
        when(event.getFather()).thenReturn(dad);
        when(event.getBreeder()).thenReturn(player);
        when(event.getEntityType()).thenReturn(EntityType.COW);
        return event;
    }

    @Test
    @DisplayName("parents bought as eggs could be bred without end; only animals raised the slow way count")
    void farmedBreeding() {
        List<Activity> heard = heard();
        listener.onBreed(bred(SpawnReason.SPAWNER_EGG, SpawnReason.NATURAL));
        listener.onBreed(bred(SpawnReason.NATURAL, SpawnReason.SPAWNER));
        assertThat(heard).isEmpty();
        listener.onBreed(bred(SpawnReason.BREEDING, SpawnReason.NATURAL));
        assertThat(heard).containsExactly(Activity.of(ActivityKind.BREED, "cow"));
    }
}
