package de.raindancer.core.content.enchant;

import de.raindancer.core.content.items.NonIngredients;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Optional;

/**
 * Shards of Rain's enchantments: one kind per enchantment, the stack carrying the enchantment's key. Dropped
 * by the enchants module, paid into a guild to unlock the enchantment, spent at the shop for its book, and
 * otherwise an item like any other — traded, sold, auctioned. Echo shards to look at, never one to craft with.
 *
 * <p>The enchantment's key is also the first custom_model_data string: a resource pack picks each shard's own
 * model by it, and without one it stays a plain echo shard.
 */
public final class EnchantShards {

    public static final NamespacedKey KEY = new NamespacedKey("rainsenchants", "shard");
    private static final Material LOOK = Material.ECHO_SHARD;

    private EnchantShards() {
    }

    /** {@code amount} shards of this enchantment, named after it. */
    public static ItemStack of(String enchantment, int amount) {
        ItemStack stack = new ItemStack(LOOK, Math.max(1, Math.min(LOOK.getMaxStackSize(), amount)));
        Component name = CustomEnchantments.find(enchantment).map(found -> found.description())
                .orElse(Component.text(enchantment));
        stack.editMeta(meta -> {
            meta.itemName(Component.empty().append(name).append(Component.text(" Shard")));
            meta.lore(List.of(
                    Component.text("Pay it into your guild to unlock", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("the enchantment; spend it at the shop", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("for its book.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, enchantment);
            CustomModelDataComponent model = meta.getCustomModelDataComponent();
            model.setStrings(List.of(enchantment));
            meta.setCustomModelDataComponent(model);
        });
        return NonIngredients.mark(stack);
    }

    /** Which enchantment's shards these are, if they are any. */
    public static Optional<String> enchantmentOf(ItemStack stack) {
        if (stack == null || stack.getType() != LOOK || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        return Optional.ofNullable(stack.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING));
    }

    /**
     * Gives a shard made before shards had their own looks the key its model is picked by.
     *
     * @return whether the stack was changed
     */
    public static boolean dress(ItemStack stack) {
        Optional<String> enchantment = enchantmentOf(stack);
        if (enchantment.isEmpty()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        CustomModelDataComponent model = meta.getCustomModelDataComponent();
        if (model.getStrings().equals(List.of(enchantment.get()))) {
            return false;
        }
        model.setStrings(List.of(enchantment.get()));
        meta.setCustomModelDataComponent(model);
        stack.setItemMeta(meta);
        return true;
    }

    /** How many shards of this enchantment the inventory holds. */
    public static int count(Inventory inventory, String enchantment) {
        int count = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (enchantmentOf(stack).filter(enchantment::equals).isPresent()) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /** Takes exactly {@code amount}, last slots first — or nothing at all, if there are fewer. */
    public static boolean take(Inventory inventory, String enchantment, int amount) {
        if (amount <= 0) {
            return true;
        }
        if (count(inventory, enchantment) < amount) {
            return false;
        }
        ItemStack[] contents = inventory.getStorageContents();
        int left = amount;
        for (int slot = contents.length - 1; slot >= 0 && left > 0; slot--) {
            ItemStack stack = contents[slot];
            if (enchantmentOf(stack).filter(enchantment::equals).isEmpty()) {
                continue;
            }
            int moved = Math.min(left, stack.getAmount());
            if (moved == stack.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - moved);
                inventory.setItem(slot, stack);
            }
            left -= moved;
        }
        return true;
    }
}
