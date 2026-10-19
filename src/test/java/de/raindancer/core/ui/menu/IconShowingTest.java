package de.raindancer.core.ui.menu;

import de.raindancer.core.testkit.TestItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A button that is a real item — the thing on sale, an auction lot — keeps the item and gains the menu's words. */
class IconShowingTest {

    private static String plain(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line);
    }

    @Test
    @DisplayName("the item keeps its own name and lore; the menu's lines come after a gap")
    void keepsTheItem() {
        ItemStack sword = TestItems.of(Material.DIAMOND_SWORD, meta -> {
            meta.displayName(Component.text("Excalibur"));
            meta.lore(List.of(Component.text("Pulled from a stone")));
        });
        ItemStack icon = Icons.showing(sword, null, List.of("<gray>Price: 10"));

        assertThat(icon).isNotSameAs(sword);
        assertThat(icon.getType()).isEqualTo(Material.DIAMOND_SWORD);
        assertThat(plain(icon.getItemMeta().displayName())).isEqualTo("Excalibur");
        assertThat(icon.getItemMeta().lore()).extracting(IconShowingTest::plain)
                .containsExactly("Pulled from a stone", "", "Price: 10");
        assertThat(sword.getItemMeta().lore()).as("the original is not touched").hasSize(1);
    }

    @Test
    @DisplayName("a name given replaces the item's; an amount is kept as a count on the button")
    void renamed() {
        ItemStack icon = Icons.showing(TestItems.of(Material.DIAMOND, 16), "<aqua>Diamonds", List.of());
        assertThat(plain(icon.getItemMeta().displayName())).isEqualTo("Diamonds");
        assertThat(icon.getAmount()).isEqualTo(16);
    }

    @Test
    @DisplayName("nothing to show is still a button")
    void nothing() {
        assertThat(Icons.showing(null, "<gray>Empty", List.of()).getType()).isEqualTo(Material.STONE);
    }
}
