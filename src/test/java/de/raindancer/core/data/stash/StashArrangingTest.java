package de.raindancer.core.data.stash;

import de.raindancer.core.testkit.TestItems;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A stash that grows, keeps no stands, and sorts itself — what a player's vault with pages needs. */
class StashArrangingTest {

    private static ItemStack of(Material material, int amount) {
        return TestItems.of(material, amount);
    }

    @Test
    @DisplayName("a bigger capacity takes more at once; a smaller one drops nothing already in")
    void resizing() {
        Stash stash = new Stash(1);
        stash.deposit(of(Material.DIRT, 64));
        assertThat(stash.deposit(of(Material.STONE, 1))).isZero();

        stash.capacity(2);
        assertThat(stash.deposit(of(Material.STONE, 1))).isEqualTo(1);

        stash.capacity(1);
        assertThat(stash.size()).as("shrinking never takes anything away").isEqualTo(2);
        assertThat(stash.deposit(of(Material.GRAVEL, 1))).isZero();
    }

    @Test
    @DisplayName("without stands, armour is an item like any other")
    void noStands() {
        Stash stash = Stash.withoutStands(5);

        stash.deposit(of(Material.DIAMOND_HELMET, 1));

        assertThat(stash.hasArmour()).isFalse();
        assertThat(stash.items()).extracting(ItemStack::getType).containsExactly(Material.DIAMOND_HELMET);
    }

    @Test
    @DisplayName("restored without stands, what stood on one comes back as an item")
    void restoredStandsBecomeItems() {
        Stash stash = Stash.restoredWithoutStands(5, List.of(of(Material.DIRT, 3)),
                Map.of(ArmourPiece.HEAD, of(Material.IRON_HELMET, 1)), List.of(), 7);

        assertThat(stash.hasArmour()).isFalse();
        assertThat(stash.items()).extracting(ItemStack::getType)
                .containsExactly(Material.DIRT, Material.IRON_HELMET);
        assertThat(stash.contents().version()).isEqualTo(7);
    }

    @Test
    @DisplayName("sorting tops up half stacks and orders what is left, and counts as one change")
    void sorting() {
        // Only reachable through restored(): two half stacks side by side.
        Stash messy = Stash.restored(10, List.of(of(Material.STONE, 10), of(Material.DIRT, 30),
                of(Material.STONE, 60), of(Material.DIRT, 20)), Map.of(), List.of(), 3);

        boolean moved = messy.sort(Comparator.comparing(item -> item.getType().name()));

        assertThat(moved).isTrue();
        assertThat(messy.items()).extracting(item -> item.getType() + "x" + item.getAmount())
                .containsExactly("DIRTx50", "STONEx64", "STONEx6");
        assertThat(messy.contents().version()).isEqualTo(4);
    }

    @Test
    @DisplayName("sorting what is already sorted changes nothing, so nothing has to be written")
    void sortingSortedIsNoChange() {
        Stash stash = new Stash(10);
        stash.deposit(of(Material.DIRT, 5));
        stash.deposit(of(Material.STONE, 5));
        long before = stash.contents().version();

        assertThat(stash.sort(Comparator.comparing(item -> item.getType().name()))).isFalse();
        assertThat(stash.contents().version()).isEqualTo(before);
    }

    @Test
    @DisplayName("different items never merge while sorting, even of one material")
    void sortingKeepsDifferentItemsApart() {
        ItemStack named = TestItems.of(Material.DIRT, meta -> meta.customName(Component.text("Special")));
        Stash stash = Stash.restored(10, List.of(of(Material.DIRT, 5), named, of(Material.DIRT, 5)),
                Map.of(), List.of());

        stash.sort(Comparator.comparing(item -> item.getType().name()));

        assertThat(stash.items()).hasSize(2);
        assertThat(stash.items().stream().mapToInt(ItemStack::getAmount).sum()).isEqualTo(11);
    }
}
