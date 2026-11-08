package de.raindancer.core.ui.menu;

import de.raindancer.core.ui.chat.Brand;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A page opened with no parent while another page is on screen is a page the player reached from that one —
 * so it goes back there. Every plugin's "open the bank" opener builds its page with {@code null}, and every
 * button that used one of those lost the way back.
 */
class MenuAdoptionTest {

    private static class Page extends Menu {
        Page(Player viewer, Menu parent) {
            super(viewer, new Brand("Test"), parent);
        }

        @Override
        protected Component title() {
            return Component.text("Page");
        }

        @Override
        protected void render() {
        }
    }

    private static final class Other extends Page {
        Other(Player viewer, Menu parent) {
            super(viewer, parent);
        }
    }

    private static Player viewer() {
        Player viewer = mock(Player.class);
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        return viewer;
    }

    private static void showing(Player viewer, InventoryHolder holder) {
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        when(viewer.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(top);
        when(top.getHolder(false)).thenReturn(holder);
        when(top.getHolder()).thenReturn(holder);
    }

    @Test
    @DisplayName("a parentless page opened over another page goes back to it")
    void adoptsThePageOnScreen() {
        Player viewer = viewer();
        Page bank = new Page(viewer, null);
        showing(viewer, bank);
        Other shop = new Other(viewer, null);
        shop.adoptOpener();
        assertThat(shop.parent()).isSameAs(bank);
    }

    @Test
    @DisplayName("a page with a parent of its own keeps it")
    void keepsAGivenParent() {
        Player viewer = viewer();
        Page bank = new Page(viewer, null);
        showing(viewer, bank);
        Page given = new Page(viewer, null);
        Other child = new Other(viewer, given);
        child.adoptOpener();
        assertThat(child.parent()).isSameAs(given);
    }

    @Test
    @DisplayName("a parent built just for the page — the shop a category hangs off — is hooked under where the player came from")
    void aSyntheticRootAdoptsToo() {
        Player viewer = viewer();
        Page bank = new Page(viewer, null);
        Other shop = new Other(viewer, bank);
        showing(viewer, shop);
        Other syntheticShop = new Other(viewer, null);
        Page category = new Page(viewer, syntheticShop);
        category.adoptOpener();
        // The synthetic shop stands in for the real one, so it goes where the real one went: back to the bank.
        assertThat(category.parent()).isSameAs(syntheticShop);
        assertThat(syntheticShop.parent()).isSameAs(bank);
    }

    @Test
    @DisplayName("going back up the chain adopts nothing — the page above is not a child of the page below")
    void goingBackIsNotAdoption() {
        Player viewer = viewer();
        Page root = new Page(viewer, null);
        Other child = new Other(viewer, root);
        showing(viewer, child);
        root.adoptOpener();
        assertThat(root.parent()).isNull();
    }

    @Test
    @DisplayName("a page of the same kind as one already in the chain takes that one's place rather than piling on")
    void aRepeatReplacesRatherThanNests() {
        Player viewer = viewer();
        Page bank = new Page(viewer, null);
        Other shop = new Other(viewer, bank);
        showing(viewer, shop);
        Page bankAgain = new Page(viewer, null);
        bankAgain.adoptOpener();
        assertThat(bankAgain.parent()).isNull();
    }

    @Test
    @DisplayName("the same page opened again over itself adopts nothing")
    void itselfIsNotAnOpener() {
        Player viewer = viewer();
        Page page = new Page(viewer, null);
        showing(viewer, page);
        page.adoptOpener();
        assertThat(page.parent()).isNull();
    }

    @Test
    @DisplayName("a window that is not one of ours is nowhere to go back to")
    void aForeignWindowIsIgnored() {
        Player viewer = viewer();
        showing(viewer, mock(InventoryHolder.class));
        Page page = new Page(viewer, null);
        page.adoptOpener();
        assertThat(page.parent()).isNull();
    }

    @Test
    @DisplayName("a page the hub closed to run a command is where the command's page goes back to")
    void aRememberedOpenerCounts() {
        Player viewer = viewer();
        Page hub = new Page(viewer, null);
        Menu.cameFrom(viewer, hub);
        Other opened = new Other(viewer, null);
        opened.adoptOpener();
        assertThat(opened.parent()).isSameAs(hub);
        // Used once: the page after that was not opened from the hub.
        Other later = new Other(viewer, null);
        later.adoptOpener();
        assertThat(later.parent()).isNull();
    }

    @Test
    @DisplayName("a remembered opener is forgotten after a moment")
    void aRememberedOpenerExpires() {
        Player viewer = viewer();
        Page hub = new Page(viewer, null);
        Menu.cameFrom(viewer, hub, 0L);
        Other opened = new Other(viewer, null);
        opened.adoptOpener();
        assertThat(opened.parent()).isNull();
    }

    @Test
    @DisplayName("no viewer, nothing to adopt")
    void noViewer() {
        Page page = new Page(null, null);
        page.adoptOpener();
        assertThat(page.parent()).isNull();
    }
}
