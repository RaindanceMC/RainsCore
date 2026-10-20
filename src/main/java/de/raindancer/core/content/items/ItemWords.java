package de.raindancer.core.content.items;

import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.choose.Category;
import de.raindancer.core.ui.text.WordSearch;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a search can find in one item — what a player would type to find it: the material as they read it, the name it
 * was given, its enchantments, a potion's effect, its lore. Searched by {@link WordSearch}.
 *
 * @param material   the material, as a player reads it ("Oak Log")
 * @param customName the name it was given, or null
 * @param extra      everything else worth finding
 * @param kind       its creative tab
 */
public record ItemWords(String material, String customName, List<String> extra, Category kind) {

    public ItemWords {
        extra = extra == null ? List.of() : List.copyOf(extra);
    }

    public static ItemWords of(ItemStack item) {
        List<String> extra = new ArrayList<>();
        String custom = null;
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        if (meta != null) {
            if (meta.hasCustomName()) {
                custom = plain(meta.customName());
            }
            enchantments(meta.getEnchants(), extra);
            if (meta instanceof EnchantmentStorageMeta book) {
                enchantments(book.getStoredEnchants(), extra);
            }
            if (meta instanceof PotionMeta potion && potion.getBasePotionType() != null) {
                extra.add(Catalogue.readable(potion.getBasePotionType().getKey().getKey()));
            }
            List<Component> lore = meta.lore();
            if (lore != null) {
                lore.forEach(line -> extra.add(plain(line)));
            }
        }
        String material = item.getType().name();
        return new ItemWords(Catalogue.readable(material), custom, extra, Catalogue.categoryOf(material));
    }

    /** Whether every typed word is somewhere in it. */
    public boolean matches(String typed) {
        return WordSearch.matches(typed, material, customName, String.join(" ", extra));
    }

    /** What a player reads it as: the given name when there is one, otherwise the material's. */
    public String shown() {
        return customName != null ? customName : material;
    }

    private static void enchantments(Map<Enchantment, Integer> enchants, List<String> into) {
        enchants.keySet().forEach(enchantment -> into.add(Catalogue.readable(enchantment.getKey().getKey())));
    }

    private static String plain(Component text) {
        return text == null ? "" : PlainTextComponentSerializer.plainText().serialize(text);
    }
}
