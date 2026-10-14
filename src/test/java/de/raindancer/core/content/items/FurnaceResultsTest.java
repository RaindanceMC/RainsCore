package de.raindancer.core.content.items;

import org.bukkit.Material;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FurnaceResultsTest {

    /** A result whose copy is itself, so the test can tell which recipe it came from. */
    private static ItemStack item() {
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        return item;
    }

    private static FurnaceRecipe furnace(ItemStack result, float experience, Material... inputs) {
        RecipeChoice.MaterialChoice choice = mock(RecipeChoice.MaterialChoice.class);
        when(choice.getChoices()).thenReturn(List.of(inputs));
        FurnaceRecipe recipe = mock(FurnaceRecipe.class);
        when(recipe.getInputChoice()).thenReturn(choice);
        when(recipe.getResult()).thenReturn(result);
        when(recipe.getExperience()).thenReturn(experience);
        return recipe;
    }

    @Test
    @DisplayName("each input of a furnace recipe smelts into its result, with its experience; anything else does not")
    void lookup() {
        ItemStack ingot = item();
        FurnaceResults results = FurnaceResults.of(List.<Recipe>of(
                furnace(ingot, 0.7f, Material.RAW_IRON, Material.IRON_ORE), mock(ShapedRecipe.class)).iterator());

        assertThat(results.of(Material.RAW_IRON)).hasValueSatisfying(each -> {
            assertThat(each.result()).isSameAs(ingot);
            assertThat(each.experience()).isEqualTo(0.7f);
        });
        assertThat(results.of(Material.IRON_ORE)).isPresent();
        assertThat(results.of(Material.STONE_BRICKS)).isEmpty();
    }

    @Test
    @DisplayName("Paper 26 hands furnace inputs over as item types: those are read too")
    @SuppressWarnings("unchecked")
    void itemTypes() {
        org.bukkit.inventory.ItemType rawGold = mock(org.bukkit.inventory.ItemType.class);
        when(rawGold.getKey()).thenReturn(org.bukkit.NamespacedKey.minecraft("raw_gold"));
        io.papermc.paper.registry.set.RegistryKeySet<org.bukkit.inventory.ItemType> set =
                mock(io.papermc.paper.registry.set.RegistryKeySet.class);
        when(set.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(rawGold));
        RecipeChoice.ItemTypeChoice choice = RecipeChoice.itemType(set);
        FurnaceRecipe recipe = mock(FurnaceRecipe.class);
        when(recipe.getInputChoice()).thenReturn(choice);
        ItemStack ingot = item();
        when(recipe.getResult()).thenReturn(ingot);

        FurnaceResults results = FurnaceResults.of(List.<Recipe>of(recipe).iterator(), types -> types.resolve(null));
        assertThat(results.of(Material.RAW_GOLD)).hasValueSatisfying(each -> assertThat(each.result()).isSameAs(ingot));
    }

    @Test
    @DisplayName("the first recipe for an input wins, as the furnace picks it")
    void firstWins() {
        ItemStack first = item();
        FurnaceResults results = FurnaceResults.of(List.<Recipe>of(furnace(first, 0.1f, Material.SAND),
                furnace(item(), 0.2f, Material.SAND)).iterator());
        assertThat(results.of(Material.SAND).orElseThrow().result()).isSameAs(first);
    }
}
