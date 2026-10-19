package de.raindancer.core.content.items;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A custom item, as the server owner configured it.
 *
 * <h2>Why a definition is not an {@code ItemStack}</h2>
 * A definition is what somebody configured; an {@code ItemStack} is one made from it. Keeping them
 * apart is what lets a definition be edited while items made from it are already in players'
 * chests, and it is what lets all of this be tested — an {@code ItemStack} needs a running server
 * and a definition does not.
 *
 * <h2>Where a bad material is caught</h2>
 * Here, only air and nothing. Whether a material can be an item at all — {@code WATER} cannot —
 * is {@link Material#isItem()}, and that reads the server's registry, so it cannot be asked while a
 * definition is merely being described. It is checked by {@link ItemFactory} instead, at the moment
 * an actual stack is made, which is the first point at which the answer both exists and matters.
 *
 * <h2>Why the key is namespaced</h2>
 * {@code claims:selection-stick}. Two plugins both wanting an item called "wand" is not a conflict
 * anybody should have to think about, and the namespace is also what lets the registry answer "show
 * me everything the claims module defines" without a plugin having to keep its own list.
 *
 * @param customModelData the custom model data a resourcepack keys off, if any
 * @param tags      whatever the owning plugin needs to remember about it
 */
public record CustomItem(String plugin, String id, Material material, String displayName,
                         List<String> lore, Integer customModelData, boolean glowing,
                         String abilityKey, List<String> recipeRows, Map<String, String> tags) {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Set on an item that must not be crafted away as the block it is made of — see {@link #isIngredient}. */
    public static final String TAG_NOT_AN_INGREDIENT = "core:not-an-ingredient";

    /**
     * The item model a resource pack defines for it ({@code rainseconomy:sell_wand}) — the 1.21.4 way, which
     * needs no override of the base item's own model the way custom model data does. A tag rather than a
     * component so definitions and plugins built against the older record keep working.
     */
    public static final String TAG_ITEM_MODEL = "core:item-model";

    private static final Pattern MODEL_KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** Something shaped like a tag, surviving into the rendered text — i.e. one nothing knows. */
    private static final Pattern UNPARSED_TAG = Pattern.compile("<[a-zA-Z_][a-zA-Z0-9_:#-]*>");

    public CustomItem {
        if (plugin == null || plugin.isBlank()) {
            throw new IllegalArgumentException("A custom item must say which plugin defines it.");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A custom item needs an id.");
        }
        if (material == null || material == Material.AIR) {
            throw new IllegalArgumentException(
                    "A custom item needs a material, and it cannot be air.");
        }
        if (displayName != null && !displayName.isBlank() && !parses(displayName)) {
            // Refused rather than stored: MiniMessage renders a tag it does not know as text, so
            // <notatag>Sword would end up in a player's inventory looking exactly like that.
            throw new IllegalArgumentException(
                    "'" + displayName + "' is not markup MiniMessage understands.");
        }
        plugin = plugin.trim().toLowerCase(Locale.ROOT);
        id = id.trim().toLowerCase(Locale.ROOT);
        lore = lore == null ? List.of() : List.copyOf(lore);
        recipeRows = recipeRows == null ? List.of() : List.copyOf(recipeRows);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        String model = tags.get(TAG_ITEM_MODEL);
        if (model != null && !MODEL_KEY.matcher(model).matches()) {
            throw new IllegalArgumentException("'" + model + "' is not an item model key — write it in full, "
                    + "namespace:path, lowercase.");
        }
    }

    /**
     * @param id what this item is called in a config file and at a command — not what a player
     *           sees, which is {@link Builder#name}
     */
    public static Builder builder(String plugin, String id) {
        return new Builder(plugin, id);
    }

    /** {@code claims:selection-stick} — unique across the server. */
    public String key() {
        return plugin + ":" + id;
    }

    /**
     * What a player sees this called, in MiniMessage. Empty means "whatever the block is called".
     *
     * <p>Distinct from {@link #id()}, which is what a config file and a command call it. Conflating
     * the two is how an item cannot be renamed without every command that gives it breaking.
     */
    public String name() {
        return displayName == null ? "" : displayName;
    }

    /** The name to show, falling back to the id when nobody has set one. */
    public String nameOrId() {
        return name().isEmpty() ? id : name();
    }

    /**
     * The custom model data a resourcepack keys off, if any.
     *
     * <p>Named differently from the component it reads — {@code customModelData} — because a
     * record's own accessor may not change its return type, and an {@link Optional} is worth more
     * here than the symmetry: most items do not have one.
     */
    public Optional<Integer> modelData() {
        return Optional.ofNullable(customModelData);
    }

    /**
     * Which {@link ItemAbility} this item does, if it does anything.
     *
     * <p>A key rather than the ability itself, so a definition can be read from a file before the
     * plugin that owns the ability has registered it — and so an item whose plugin is switched off
     * is an inert item rather than an unreadable definition.
     */
    public Optional<String> ability() {
        return abilityKey == null || abilityKey.isBlank()
                ? Optional.empty() : Optional.of(abilityKey);
    }

    /**
     * The ability's full key, as {@link ItemAbilities} holds it — {@code hungergames:medikit}.
     *
     * <p>An item may name its ability with a bare id, and every one written so far does:
     * {@code .ability(MEDIKIT)} beside {@code CustomItem.builder(PLUGIN, MEDIKIT)} reads better than
     * repeating the namespace, and repeating it is a second place for a plugin to be renamed. So the
     * namespace is filled in here, from the item's own — an item and its ability belong to the same
     * plugin or they are not each other's.
     *
     * <p>This is what anything dispatching a use must look up with. It matters more than it reads:
     * {@code abilities.byKey("medikit")} finds nothing, and an ability nobody can find is an item that
     * does nothing at all when it is clicked — no error, no log line, just a stick.
     */
    public Optional<String> abilityInFull() {
        return ability().map(named -> named.indexOf(':') >= 0 ? named : plugin + ":" + named);
    }

    /**
     * The crafting recipe, as up to three rows of material names — {@code "LIGHTNING_ROD
     * DIAMOND_BLOCK LIGHTNING_ROD"}. Empty for an item nobody can craft.
     *
     * <p>Rows of text rather than a Bukkit {@code ShapedRecipe} for the same reason the ability is a
     * key: this is what a server owner writes in a config file and edits in a menu, and it has to
     * survive being read before there is a server to register it with. {@link ItemRecipes} turns it
     * into the real thing.
     */
    public List<String> recipe() {
        return recipeRows;
    }

    public boolean isCraftable() {
        return !recipeRows.isEmpty();
    }

    /**
     * Whether it may go into a crafting grid as the vanilla item it is made of.
     *
     * <p>True unless marked: a server may have a custom emerald it expects to be crafted with. A token
     * that is a nether star is not one of those — it would make a beacon.
     */
    public boolean isIngredient() {
        return tag(TAG_NOT_AN_INGREDIENT).isEmpty();
    }

    /** The resource pack's item model it wears, if any — see {@link #TAG_ITEM_MODEL}. */
    public Optional<String> itemModel() {
        return tag(TAG_ITEM_MODEL);
    }

    public boolean isGlowing() {
        return glowing;
    }

    public Optional<String> tag(String key) {
        return Optional.ofNullable(tags.get(key));
    }

    public CustomItem withMaterial(Material newMaterial) {
        return new CustomItem(plugin, id, newMaterial, displayName, lore, customModelData, glowing, abilityKey, recipeRows, tags);
    }

    public CustomItem withName(String newDisplayName) {
        return new CustomItem(plugin, id, material, newDisplayName, lore, customModelData, glowing, abilityKey, recipeRows, tags);
    }

    public CustomItem withLore(List<String> newLore) {
        return new CustomItem(plugin, id, material, displayName, newLore, customModelData, glowing, abilityKey, recipeRows, tags);
    }

    public CustomItem withModelData(Integer newModelData) {
        return new CustomItem(plugin, id, material, displayName, lore, newModelData, glowing, abilityKey, recipeRows, tags);
    }

    /**
     * The same item pointing at a different ability.
     *
     * <p>Its own method because the ability is the one part of a definition that is <em>code</em> rather
     * than configuration — see {@link CustomItems#defineIfAbsent}.
     */
    public CustomItem withAbility(String newAbility) {
        return new CustomItem(plugin, id, material, displayName, lore, customModelData, glowing, newAbility,
                recipeRows, tags);
    }

    public CustomItem withGlowing(boolean nowGlowing) {
        return new CustomItem(plugin, id, material, displayName, lore, customModelData, nowGlowing, abilityKey, recipeRows, tags);
    }

    /**
     * The same item, crafted from something else.
     *
     * <h2>Why this was missing, and what it cost</h2>
     * There was a {@code with…} for the material, the name, the lore, the model data, the glow and a tag — and
     * none for the rows. So a server owner could <em>see</em> an item's recipe and never change it, and the
     * plugin that used to carry a page for exactly that had been ported onto this registry. An item whose
     * recipe cannot be edited is one somebody has to delete and rebuild.
     *
     * @param rows up to three rows of space-separated material names; {@code null} or empty removes the
     *             recipe, which is how a screen says "this is not craftable" without building an empty list
     */
    public CustomItem withRecipe(List<String> rows) {
        return new CustomItem(plugin, id, material, displayName, lore, customModelData, glowing, abilityKey,
                rows == null ? List.of() : List.copyOf(rows), tags);
    }

    public CustomItem withTag(String key, String value) {
        Map<String, String> updated = new LinkedHashMap<>(tags);
        if (value == null) {
            updated.remove(key);
        } else {
            updated.put(key, value);
        }
        return new CustomItem(plugin, id, material, displayName, lore, customModelData, glowing, abilityKey, recipeRows, updated);
    }

    private static boolean parses(String miniMessage) {
        try {
            String rendered = PlainTextComponentSerializer.plainText()
                    .serialize(MINI.deserialize(miniMessage));
            return !UNPARSED_TAG.matcher(rendered).find();
        } catch (RuntimeException broken) {
            return false;
        }
    }

    /** Builds one. Only the plugin, the name and the material are required. */
    public static final class Builder {
        private final String plugin;
        private final String id;
        private Material material;
        private String displayName;
        private List<String> lore = List.of();
        private Integer modelData;
        private boolean glowing;
        private String ability;
        private List<String> recipe = List.of();
        private final Map<String, String> tags = new LinkedHashMap<>();

        private Builder(String plugin, String id) {
            this.plugin = plugin;
            this.id = id;
        }

        public Builder material(Material value) {
            this.material = value;
            return this;
        }

        /** What a player sees it called, in MiniMessage. */
        public Builder name(String value) {
            this.displayName = value;
            return this;
        }

        public Builder lore(List<String> value) {
            this.lore = value;
            return this;
        }

        public Builder modelData(Integer value) {
            this.modelData = value;
            return this;
        }

        public Builder glowing(boolean value) {
            this.glowing = value;
            return this;
        }

        /** Which ability this item performs, e.g. {@code "hg:lightning"}. */
        public Builder ability(String value) {
            this.ability = value;
            return this;
        }

        /** How it is crafted: up to three rows, each of up to three material names or {@code -}. */
        public Builder recipe(List<String> rows) {
            this.recipe = rows;
            return this;
        }

        public Builder tag(String key, String value) {
            if (key != null && value != null) {
                tags.put(key, value);
            }
            return this;
        }

        /** The item model a resource pack defines, in full: {@code rainseconomy:sell_wand}. */
        public Builder itemModel(String key) {
            return tag(TAG_ITEM_MODEL, key);
        }

        /** Never accepted by a crafting grid — see {@link CustomItem#isIngredient}. */
        public Builder notAnIngredient() {
            return tag(TAG_NOT_AN_INGREDIENT, "true");
        }

        public CustomItem build() {
            return new CustomItem(plugin, id, material, displayName, lore, modelData, glowing,
                    ability, recipe, tags);
        }
    }
}
