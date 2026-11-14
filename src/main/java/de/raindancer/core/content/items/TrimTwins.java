package de.raindancer.core.content.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.SmithingTrimRecipe;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Armour trims on things that are not armour — elytra, horse armour: for every trim recipe the server has (vanilla's
 * and any a datapack adds) a twin whose base is one of {@link #materials()}. The game's trim recipe never checks what
 * the base is, so the smithing table, its preview, shift-clicking and the tooltip all work as for armour. What the trim
 * looks like is the resource pack's: {@code trims/entity/<layer>/<pattern>} for whatever layer the item draws.
 */
public final class TrimTwins {

    private final Server server;
    private final String namespace;
    private final String prefix;
    private final Set<Material> materials;
    private final List<NamespacedKey> added = new ArrayList<>();
    /** Every twin any plugin has added: a twin is a trim recipe too, and mirroring it again would duplicate it. */
    private static final java.util.Set<NamespacedKey> ALL_TWINS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * @param namespace the twins' namespace, the plugin's own
     * @param prefix    what the twins are called under it: {@code <prefix>/<original namespace>/<original key>}
     */
    public TrimTwins(Server server, String namespace, String prefix, Set<Material> materials) {
        this.server = server;
        this.namespace = namespace;
        this.prefix = prefix;
        this.materials = Set.copyOf(materials);
    }

    public Set<Material> materials() {
        return materials;
    }

    public String prefix() {
        return prefix;
    }

    /** The twin's name for a trim recipe, or empty when it gets none: our own, base takes these already, no pattern. */
    public static Optional<NamespacedKey> twinKey(NamespacedKey original, String namespace, String prefix,
                                                  boolean hasPattern, boolean baseTakesThese) {
        if (!hasPattern || baseTakesThese || original.getNamespace().equals(namespace)) {
            return Optional.empty();
        }
        return Optional.of(new NamespacedKey(namespace, prefix + "/" + original.getNamespace() + "/" + original.getKey()));
    }

    /** Whether a recipe's base already takes one of these — then a datapack did it and no twin is needed. */
    boolean takes(RecipeChoice base) {
        return base instanceof RecipeChoice.MaterialChoice choice
                && choice.getChoices().stream().anyMatch(materials::contains);
    }

    static boolean isTwin(NamespacedKey key) {
        return ALL_TWINS.contains(key);
    }

    static void remember(NamespacedKey key) {
        ALL_TWINS.add(key);
    }

    static void forget(NamespacedKey key) {
        ALL_TWINS.remove(key);
    }

    List<SmithingTrimRecipe> mirror(Iterable<? extends Recipe> recipes) {
        RecipeChoice base = new RecipeChoice.MaterialChoice(materials.stream().sorted().toList());
        List<SmithingTrimRecipe> twins = new ArrayList<>();
        for (Recipe recipe : recipes) {
            if (recipe instanceof SmithingTrimRecipe trim && !isTwin(trim.getKey())) {
                twinKey(trim.getKey(), namespace, prefix, trim.getTrimPattern() != null, takes(trim.getBase()))
                        .ifPresent(key -> twins.add(new SmithingTrimRecipe(key, trim.getTemplate(), base,
                                trim.getAddition(), trim.getTrimPattern(), trim.willCopyDataComponents())));
            }
        }
        return twins;
    }

    /** Adds the twins, replacing any from before — after a datapack reload the server's recipes may have changed. */
    public synchronized int register() {
        unregister();
        List<Recipe> current = new ArrayList<>();
        for (Iterator<Recipe> it = server.recipeIterator(); it.hasNext(); ) {
            current.add(it.next());
        }
        List<SmithingTrimRecipe> twins = mirror(current);
        for (int i = 0; i < twins.size(); i++) {
            SmithingTrimRecipe twin = twins.get(i);
            if (server.addRecipe(twin, i == twins.size() - 1)) {
                added.add(twin.getKey());
                remember(twin.getKey());
            }
        }
        return added.size();
    }

    public synchronized void unregister() {
        for (int i = 0; i < added.size(); i++) {
            server.removeRecipe(added.get(i), i == added.size() - 1);
            forget(added.get(i));
        }
        added.clear();
    }

    /** Whether every twin is really in the server's recipes — the startup check. */
    public synchronized boolean allPresent() {
        return added.stream().allMatch(key -> server.getRecipe(key) instanceof SmithingTrimRecipe);
    }

    /** Re-registers after {@code /minecraft:reload}, which can add, change or drop trim recipes; told how many. */
    public Listener followReloads(IntConsumer registered) {
        return new Listener() {
            @EventHandler
            public void reloaded(ServerResourcesReloadedEvent event) {
                registered.accept(register());
            }
        };
    }
}
