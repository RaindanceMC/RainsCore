package de.raindancer.core.content.enchant;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.data.EnchantmentRegistryEntry;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.EnchantmentKeys;
import io.papermc.paper.registry.set.RegistrySet;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.List;
import java.util.Optional;

/**
 * Adding enchantments to the game, and finding them again.
 *
 * <p>{@link #register} runs in a plugin's <em>bootstrapper</em>: Paper freezes the enchantment registry
 * before {@code onEnable}, so a registration made there is too late — silently.
 *
 * <p>Which enchantments one excludes is a <em>tag</em> ({@code <namespace>:exclusive_set/<name>}), as vanilla does it,
 * never a list of enchantments: Geyser reads a list while the registry is still being built, fails, and Bedrock
 * players then see none of their items.
 */
public final class CustomEnchantments {

    /** Switched off by the server's owner: still registered, so items keep them, but read as absent. */
    private static volatile java.util.Set<String> switchedOff = java.util.Set.of();

    private CustomEnchantments() {
    }

    /** Replaces the switched-off enchantments, by full key. */
    public static void switchOff(java.util.Collection<String> keys) {
        switchedOff = keys == null ? java.util.Set.of() : keys.stream()
                .map(key -> key.toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static boolean isSwitchedOff(String key) {
        return key != null && switchedOff.contains(key.toLowerCase(java.util.Locale.ROOT));
    }

    public static void register(BootstrapContext context, List<EnchantDef> definitions) {
        if (definitions.isEmpty()) {
            return;
        }
        context.getLifecycleManager().registerEventHandler(
                io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.TAGS.postFlatten(RegistryKey.ENCHANTMENT),
                event -> {
                    for (EnchantDef def : definitions) {
                        if (!def.exclusiveWith().isEmpty()) {
                            event.registrar().setTag(exclusiveTag(def), def.exclusiveWith().stream()
                                    .map(other -> TypedKey.create(RegistryKey.ENCHANTMENT, Key.key(other)))
                                    .toList());
                        }
                    }
                });
        context.getLifecycleManager().registerEventHandler(RegistryEvents.ENCHANTMENT.compose().newHandler(event -> {
            var items = RegistryAccess.registryAccess().getRegistry(RegistryKey.ITEM);
            for (EnchantDef def : definitions) {
                List<TypedKey<ItemType>> supported = items.keyStream()
                        .filter(key -> def.gear().matches(key.value()))
                        .map(key -> TypedKey.create(RegistryKey.ITEM, key))
                        .toList();
                event.registry().register(EnchantmentKeys.create(Key.key(def.namespace(), def.path())), builder -> builder
                        .description(MiniMessage.miniMessage().deserialize(def.name()))
                        .supportedItems(RegistrySet.keySet(RegistryKey.ITEM, supported))
                        .weight(def.weight())
                        .maxLevel(def.maxLevel())
                        .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(1, 10))
                        .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(21, 10))
                        .anvilCost(def.anvilCost())
                        .activeSlots(def.gear().slot())
                        .exclusiveWith(def.exclusiveWith().isEmpty()
                                ? RegistrySet.keySet(RegistryKey.ENCHANTMENT, List.of())
                                : event.getOrCreateTag(exclusiveTag(def))));
            }
        }));
    }

    /** The tag of what {@code def} excludes. */
    static io.papermc.paper.registry.tag.TagKey<Enchantment> exclusiveTag(EnchantDef def) {
        return io.papermc.paper.registry.tag.TagKey.create(RegistryKey.ENCHANTMENT,
                Key.key(def.namespace(), "exclusive_set/" + def.path()));
    }

    public static Optional<Enchantment> find(String key) {
        NamespacedKey parsed = key == null ? null : NamespacedKey.fromString(key);
        return parsed == null ? Optional.empty() : Optional.ofNullable(Registry.ENCHANTMENT.get(parsed));
    }

    /** The level of this enchantment on the item, 0 if it has none, is air, or the enchantment is unknown or switched off. */
    public static int levelOn(ItemStack item, String key) {
        if (item == null || isSwitchedOff(key) || item.getType().isAir()) {
            return 0;
        }
        return find(key).map(item::getEnchantmentLevel).orElse(0);
    }

    /** An enchanted book holding this enchantment — what an anvil applies. */
    public static ItemStack book(String key, int level) {
        Enchantment enchantment = find(key).orElseThrow(() ->
                new IllegalArgumentException("No enchantment '" + key + "' is registered."));
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(enchantment, level, true);
        book.setItemMeta(meta);
        return book;
    }

}
