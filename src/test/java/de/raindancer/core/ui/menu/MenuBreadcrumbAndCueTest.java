package de.raindancer.core.ui.menu;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.effect.Cues;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MenuBreadcrumbAndCueTest {

    private static final class Titled extends Menu {
        private final Component title;

        Titled(Component title) {
            super(mock(Player.class), new Brand("Test"), null);
            this.title = title;
        }

        @Override
        protected Component title() {
            if (title == null) {
                throw new IllegalStateException("gone");
            }
            return title;
        }

        @Override
        protected void render() {
        }
    }

    private static final class Paged extends PaginatedMenu<String> {
        Paged() {
            super(mock(Player.class), new Brand("Test"), null);
        }

        @Override
        protected Component title() {
            return Component.text("List");
        }

        @Override
        protected List<String> entries() {
            return List.of();
        }

        @Override
        protected ItemStack icon(String entry) {
            return null;
        }

        @Override
        protected void onClick(String entry, org.bukkit.event.inventory.InventoryClickEvent event) {
        }
    }

    @Test
    @DisplayName("Back says the page's title rather than 'the previous menu'")
    void breadcrumbIsTheTitle() {
        assertThat(new Titled(MiniMessage.miniMessage().deserialize("<dark_gray>Teleport <white>requests")).breadcrumb())
                .isEqualTo("Teleport requests");
    }

    @Test
    @DisplayName("a page whose title can no longer be said still has a breadcrumb")
    void breadcrumbSurvivesATitleThatThrows() {
        assertThat(new Titled(null).breadcrumb()).isEqualTo("the previous menu");
        assertThat(new Titled(Component.text("   ")).breadcrumb()).isEqualTo("the previous menu");
    }

    @Test
    @DisplayName("every button clicks; a paged list's arrows turn a page")
    void cues() {
        Titled page = new Titled(Component.text("Page"));
        assertThat(page.clickCue(10)).isEqualTo(Cues.CLICK);
        Paged list = new Paged();
        int chrome = list.layout().chromeRowStart();
        assertThat(list.clickCue(chrome + MenuLayout.CHROME_PREVIOUS)).isEqualTo(Cues.PAGE);
        assertThat(list.clickCue(chrome + MenuLayout.CHROME_NEXT)).isEqualTo(Cues.PAGE);
        assertThat(list.clickCue(3)).isEqualTo(Cues.CLICK);
    }
}
