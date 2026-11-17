package de.raindancer.core.ui.cosmetic;

import de.raindancer.core.ui.menu.Menu;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CosmeticDoorsTest {

    @AfterEach
    void forget() {
        CosmeticDoors.clear();
    }

    private static CosmeticDoor door(String id) {
        return new CosmeticDoor() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public ItemStack icon(Player viewer) {
                return null;
            }

            @Override
            public Optional<String> locked(Player viewer) {
                return Optional.empty();
            }

            @Override
            public void open(Player viewer, Menu parent) {
            }
        };
    }

    /** A door that remembers whether its cosmetic is worn, as a plugin's own would. */
    private static final class Worn implements CosmeticDoor {

        private final String id;
        private boolean on;
        private final boolean breaks;

        private Worn(String id, boolean on, boolean breaks) {
            this.id = id;
            this.on = on;
            this.breaks = breaks;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ItemStack icon(Player viewer) {
            return null;
        }

        @Override
        public Optional<String> locked(Player viewer) {
            return Optional.empty();
        }

        @Override
        public void open(Player viewer, Menu parent) {
        }

        @Override
        public boolean wearing(Player viewer) {
            return on;
        }

        @Override
        public void takeOff(Player viewer) {
            if (breaks) {
                throw new IllegalStateException("broken plugin");
            }
            on = false;
        }
    }

    private static final class Page extends Menu {

        private Page(Player viewer) {
            super(viewer, new de.raindancer.core.ui.chat.Brand("Test"), null);
        }

        @Override
        protected net.kyori.adventure.text.Component title() {
            return net.kyori.adventure.text.Component.text("Cosmetics");
        }

        @Override
        protected void render() {
        }
    }

    @Test
    @DisplayName("a door that says nothing about wearing wears nothing, and taking it off does nothing")
    void quietDoor() {
        Player viewer = mock(Player.class);
        CosmeticDoor quiet = door("pets:looks");
        assertThat(quiet.wearing(viewer)).isFalse();
        quiet.takeOff(viewer);
    }

    @Test
    @DisplayName("taking everything off asks every door that is worn, and a broken one does not stop the rest")
    void takeOffAll() {
        Player viewer = mock(Player.class);
        Worn chat = new Worn("chat:colour", true, false);
        Worn ctf = new Worn("ctf:looks", false, false);
        Worn broken = new Worn("elytra:looks", true, true);
        Worn rods = new Worn("fishing:rod-skins", true, false);
        CosmeticDoors.provide(null, chat);
        CosmeticDoors.provide(null, ctf);
        CosmeticDoors.provide(null, broken);
        CosmeticDoors.provide(null, rods);

        assertThat(CosmeticDoors.wearingAny(viewer)).isTrue();
        assertThat(CosmeticDoors.takeOffAll(viewer)).isEqualTo(2);
        assertThat(chat.wearing(viewer)).isFalse();
        assertThat(rods.wearing(viewer)).isFalse();
        assertThat(broken.wearing(viewer)).isTrue();
    }

    @Test
    @DisplayName("nobody wears anything when no door is offered")
    void nothingOffered() {
        Player viewer = mock(Player.class);
        assertThat(CosmeticDoors.wearingAny(viewer)).isFalse();
        assertThat(CosmeticDoors.takeOffAll(viewer)).isZero();
    }

    @Test
    @DisplayName("a door's own command goes back to the cosmetics page while one is hosted, else to its own")
    void backToTheCosmeticsPage() {
        Player viewer = mock(Player.class);
        Menu own = new Page(viewer);
        assertThat(CosmeticDoors.page(viewer)).isEmpty();
        assertThat(CosmeticDoors.backTo(viewer, own)).isSameAs(own);
        assertThat(CosmeticDoors.backTo(viewer, null)).isNull();

        CosmeticDoors.Page page = Page::new;
        CosmeticDoors.host(page);
        assertThat(CosmeticDoors.hosted()).isTrue();
        assertThat(CosmeticDoors.page(viewer)).get().isInstanceOf(Page.class).isNotSameAs(own);
        assertThat(CosmeticDoors.backTo(viewer, own)).isInstanceOf(Page.class).isNotSameAs(own);

        CosmeticDoors.unhost(page);
        assertThat(CosmeticDoors.page(viewer)).isEmpty();
    }

    @Test
    @DisplayName("a page hosted the old way, without saying how to build it, still counts as hosted but is not a page")
    void oldHost() {
        Player viewer = mock(Player.class);
        Runnable old = () -> { };
        CosmeticDoors.host(old);
        assertThat(CosmeticDoors.hosted()).isTrue();
        assertThat(CosmeticDoors.page(viewer)).isEmpty();
    }

    @Test
    @DisplayName("doors come in id order, each id once; retracted, a door is gone")
    void doors() {
        CosmeticDoor rods = door("fishing:rod-skins");
        CosmeticDoors.provide(null, door("pets:pets"));
        CosmeticDoors.provide(null, rods);
        CosmeticDoors.provide(null, door("fishing:rod-skins"));
        assertThat(CosmeticDoors.doors()).extracting(CosmeticDoor::id).containsExactly("fishing:rod-skins", "pets:pets");
        CosmeticDoors.retract(rods);
        assertThat(CosmeticDoors.doors()).extracting(CosmeticDoor::id).containsExactly("pets:pets");
    }

    @Test
    @DisplayName("whether a cosmetics page shows the doors: only while one says so")
    void hosted() {
        assertThat(CosmeticDoors.hosted()).isFalse();
        Object page = new Object();
        CosmeticDoors.host(page);
        assertThat(CosmeticDoors.hosted()).isTrue();
        CosmeticDoors.unhost(page);
        assertThat(CosmeticDoors.hosted()).isFalse();
    }
}
