package de.raindancer.core.content.items;

import io.papermc.paper.registry.set.RegistryKeySet;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** What an item comes out of a furnace as, and the experience for it — read from the server's own recipes. */
public final class FurnaceResults {

    public record Result(ItemStack result, float experience) {

        /** A fresh copy of the result, so whoever changes its amount does not change the recipe. */
        @Override
        public ItemStack result() {
            return result.clone();
        }
    }

    private final Map<Material, Result> results;

    private FurnaceResults(Map<Material, Result> results) {
        this.results = results;
    }

    /** The server's furnace recipes as they are now; read again after a data pack reload to see new ones. */
    public static FurnaceResults server() {
        return of(Bukkit.recipeIterator(), types -> types.resolve(Registry.ITEM));
    }

    /** Recipes whose inputs are materials only, as plugins build them. */
    public static FurnaceResults of(Iterator<Recipe> recipes) {
        return of(recipes, types -> List.of());
    }

    /**
     * @param resolve the item types of a set — a tag's members only the registry knows, so it is passed in
     */
    public static FurnaceResults of(Iterator<Recipe> recipes,
                                    Function<RegistryKeySet<ItemType>, Collection<ItemType>> resolve) {
        Map<Material, Result> results = new EnumMap<>(Material.class);
        while (recipes.hasNext()) {
            if (!(recipes.next() instanceof FurnaceRecipe furnace)) {
                continue;
            }
            Result result = new Result(furnace.getResult(), furnace.getExperience());
            for (Material input : inputs(furnace.getInputChoice(), resolve)) {
                results.putIfAbsent(input, result);
            }
        }
        return new FurnaceResults(results);
    }

    // Paper 26 hands the game's own recipes over as item types; those are material choices too, but reading them
    // as materials needs the registry, so they are read as item types first.
    private static List<Material> inputs(RecipeChoice choice,
                                         Function<RegistryKeySet<ItemType>, Collection<ItemType>> resolve) {
        if (choice instanceof RecipeChoice.ItemTypeChoice types) {
            try {
                return resolve.apply(types.itemTypes()).stream()
                        .map(type -> Material.matchMaterial(type.getKey().toString()))
                        .filter(java.util.Objects::nonNull).toList();
            } catch (RuntimeException unreadable) {
                return List.of();
            }
        }
        if (choice instanceof RecipeChoice.MaterialChoice materials) {
            return materials.getChoices();
        }
        return List.of();
    }

    public Optional<Result> of(Material input) {
        return Optional.ofNullable(results.get(input));
    }
}
