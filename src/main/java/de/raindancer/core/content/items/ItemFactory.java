package de.raindancer.core.content.items;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns a {@link CustomItem} into an actual stack, and recognises one again.
 *
 * <h2>Why recognising matters as much as making</h2>
 * Every plugin here that made an item also had to answer "is the thing in this player's hand mine?",
 * and each answered it differently — one compared display names, which meant an anvil could forge a
 * counterfeit; one compared lore, same problem. The key goes in the item's persistent data
 * container, which a player cannot edit in survival, and {@link #keyOf} is the only way anything
 * asks.
 *
 * <p>This is the half that needs a server, which is why it is separate from {@link CustomItem}:
 * everything about what an item <em>is</em> can be tested, and only the making of a real stack
 * cannot.
 */
public final class ItemFactory {

    private static final LogChannel log = Log.of("items");
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Where a custom item's key is written, so it survives being moved, dropped and picked up. */
    private final NamespacedKey marker;

    public ItemFactory(Plugin plugin) {
        this.marker = new NamespacedKey(plugin, "custom-item");
    }

    /**
     * One stack of this item.
     *
     * @return the stack, or empty when the material cannot actually be an item — {@code WATER} is a
     *         material and is not something anybody can hold
     */
    public Optional<ItemStack> create(CustomItem definition, int amount) {
        if (definition == null) {
            return Optional.empty();
        }
        Material material = definition.material();
        if (!material.isItem()) {
            // The check that could not be made when the definition was written: isItem() reads the
            // server's registry, so this is the first moment the answer exists.
            log.warn("'{}' is defined as {}, which cannot be an item; nothing was given.",
                    definition.key(), material);
            return Optional.empty();
        }
        ItemStack stack = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.of(stack);
        }
        if (!definition.name().isEmpty()) {
            // Italics off explicitly: Minecraft draws a custom name slanted unless told otherwise.
            meta.displayName(MINI.deserialize(definition.name())
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (!definition.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>(definition.lore().size());
            for (String line : definition.lore()) {
                lore.add(MINI.deserialize(line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        }
        definition.modelData().ifPresent(number -> {
            // What setCustomModelData(int) did: the number as the first float, which a resource pack's
            // "custom_model_data" range dispatch reads.
            CustomModelDataComponent model = meta.getCustomModelDataComponent();
            model.setFloats(List.of(number.floatValue()));
            meta.setCustomModelDataComponent(model);
        });
        definition.itemModel().map(org.bukkit.NamespacedKey::fromString).ifPresent(meta::setItemModel);
        if (definition.isGlowing()) {
            // An enchantment nobody can see, purely for the shimmer — the usual way of doing this,
            // and the flag is what stops "Unbreaking I" appearing in the tooltip.
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, definition.key());
        stack.setItemMeta(meta);
        return Optional.of(stack);
    }

    public Optional<ItemStack> create(CustomItem definition) {
        return create(definition, 1);
    }

    /**
     * Which custom item this stack is, if it is one.
     *
     * <p>By the key in its persistent data container and nothing else. Comparing display names or
     * lore — which is what the plugins used to do — means an anvil and a book can forge one.
     */
    public Optional<String> keyOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        return Optional.ofNullable(
                meta.getPersistentDataContainer().get(marker, PersistentDataType.STRING));
    }

    /** Whether this stack is that custom item. */
    public boolean is(ItemStack stack, String key) {
        return key != null && keyOf(stack).map(key::equalsIgnoreCase).orElse(false);
    }

    /**
     * Takes one of a custom item out of an inventory, wherever it is in there.
     *
     * <p>For the case an item's own click cannot cover: something that is <em>paid for later</em>. The
     * Hunger Games medikit works three seconds after it is used, and is only spent when it works — so at
     * that moment there is no click, no held stack and no event, just a player who may or may not still have
     * one. Somebody who dropped it, traded it or put it in a chest during those three seconds does not get
     * healed by an item they no longer have, and this returning {@code false} is how that is known.
     *
     * <p>By the persistent-data key and nothing else, like everything else here: comparing display names
     * would let an anvil and a book forge one.
     *
     * @return whether one was found and taken
     */
    public boolean takeOne(Inventory inventory, String key) {
        if (inventory == null || key == null) {
            return false;
        }
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || !is(stack, key)) {
                continue;
            }
            if (stack.getAmount() > 1) {
                stack.setAmount(stack.getAmount() - 1);
            } else {
                inventory.setItem(slot, null);
            }
            return true;
        }
        return false;
    }
}
