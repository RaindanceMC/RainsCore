package de.raindancer.core.ui.menu;

import de.raindancer.core.ui.chat.Brand;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A painted window for everybody wearing the server's pack, the classic one for everybody else.
 *
 * <p>The painting is a font glyph in the window title, so the whole style is a question of what the title says —
 * which is why it can be checked without a server.
 */
class MenuBackdropTest {

    private static final Brand BRAND = new Brand("Shop");
    private static final UUID WEARING = UUID.randomUUID();
    private static final UUID BARE = UUID.randomUUID();

    private static final class Page extends Menu {

        private final Backdrop backdrop;

        private Page(Player viewer, int rows, Backdrop backdrop) {
            super(viewer, BRAND, null, rows);
            this.backdrop = backdrop;
        }

        private Page(Player viewer, int rows) {
            super(viewer, BRAND, null, rows);
            this.backdrop = Backdrop.house(rows);
        }

        @Override
        protected Component title() {
            return Component.text("Tools");
        }

        @Override
        protected void render() {
        }

        @Override
        protected Backdrop backdrop() {
            return backdrop;
        }
    }

    private static Player viewer(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static void assertClassic(Page page) {
        Component shown = page.shownTitle();
        assertThat(shown.font()).isNull();
        assertThat(plain(shown)).isEqualTo(plain(page.windowTitle()));
    }

    @AfterEach
    void forget() {
        MenuStyle.uninstall();
    }

    @Test
    @DisplayName("Without a pack check installed, every window is the classic one")
    void classicByDefault() {
        Page page = new Page(viewer(WEARING), 6);

        assertClassic(page);
        assertThat(page.designed()).isFalse();
    }

    @Test
    @DisplayName("Somebody wearing the pack gets the painting in front of the same title")
    void wearingGetsTheBackdrop() {
        MenuStyle.install(Set.of(WEARING)::contains);
        Page page = new Page(viewer(WEARING), 6);

        Component shown = page.shownTitle();

        assertThat(page.designed()).isTrue();
        assertThat(shown).isInstanceOf(TextComponent.class);
        TextComponent glyphs = (TextComponent) shown;
        assertThat(glyphs.content()).isEqualTo(Backdrop.house(6).glyphs());
        assertThat(glyphs.font()).isEqualTo(Backdrop.FONT);
        assertThat(glyphs.color()).as("white, or the painting is tinted").isEqualTo(NamedTextColor.WHITE);
        assertThat(plain(shown)).endsWith(plain(page.windowTitle()));
    }

    @Test
    @DisplayName("The title after the painting is in the game's font unless a backdrop asks for the title font")
    void titleIsInTheGamesFont() {
        MenuStyle.install(Set.of(WEARING)::contains);
        Component shown = new Page(viewer(WEARING), 6).shownTitle();

        Component title = shown.children().getFirst();
        assertThat(title.font()).isEqualTo(Key.key("minecraft", "default"));
        assertThat(title.color()).isEqualTo(Backdrop.house(6).ink());
    }

    @Test
    @DisplayName("Somebody without the pack keeps the classic window")
    void bareKeepsTheClassic() {
        MenuStyle.install(Set.of(WEARING)::contains);
        Page page = new Page(viewer(BARE), 6);

        assertThat(page.designed()).isFalse();
        assertClassic(page);
    }

    @Test
    @DisplayName("A page can opt out of the painting")
    void optOut() {
        MenuStyle.install(Set.of(WEARING)::contains);
        Page page = new Page(viewer(WEARING), 6, null);

        assertThat(page.designed()).isFalse();
        assertClassic(page);
    }

    @Test
    @DisplayName("Every height of window has its own painting, because the slots are in different places")
    void onePaintingPerHeight() {
        assertThat(java.util.stream.IntStream.rangeClosed(1, 6).mapToObj(Backdrop::house).map(Backdrop::glyphs)
                .distinct().count()).isEqualTo(6);
        assertThat(java.util.stream.IntStream.rangeClosed(1, 6).mapToObj(Backdrop::workshop).map(Backdrop::glyphs)
                .distinct().count()).isEqualTo(6);
        assertThat(Backdrop.house(6).glyphs()).isNotEqualTo(Backdrop.workshop(6).glyphs());
    }

    @Test
    @DisplayName("A height outside the chest's one to six is clamped rather than a missing glyph")
    void clamped() {
        assertThat(Backdrop.house(0)).isEqualTo(Backdrop.house(1));
        assertThat(Backdrop.house(9)).isEqualTo(Backdrop.house(6));
    }

    @Test
    @DisplayName("A plugin's own backdrop uses its own font and ink")
    void ownBackdrop() {
        MenuStyle.install(Set.of(WEARING)::contains);
        Backdrop stall = new Backdrop(Key.key("rainseconomy", "stall"), "",
                TextColor.color(0x3B2412));
        Component shown = new Page(viewer(WEARING), 6, stall).shownTitle();

        assertThat(shown.font()).isEqualTo(Key.key("rainseconomy", "stall"));
        assertThat(shown.children().getFirst().color()).isEqualTo(TextColor.color(0x3B2412));
    }

    @Test
    @DisplayName("A pack check that throws is a classic window, not a broken menu")
    void failingCheck() {
        MenuStyle.install(id -> {
            throw new IllegalStateException("packs not loaded");
        });
        Page page = new Page(viewer(WEARING), 6);

        assertThat(page.designed()).isFalse();
    }

    @Test
    @DisplayName("A menu with no viewer — a test, a preview — is classic")
    void noViewer() {
        MenuStyle.install(id -> true);
        assertThat(new Page(null, 6).designed()).isFalse();
    }

    @Test
    @DisplayName("A title part too dark for a dark painting takes the painting's ink; brighter parts keep their colour")
    void darkPartsBecomeInk() {
        Backdrop dark = Backdrop.house(6);
        Component framed = dark.frame(Component.text("Shop").color(NamedTextColor.DARK_GRAY)
                .append(Component.text("!").color(TextColor.color(0xC9A0FF))));
        Component title = framed.children().getFirst();
        assertThat(title.color()).isEqualTo(dark.ink());
        assertThat(title.children().getFirst().color()).isEqualTo(TextColor.color(0xC9A0FF));
    }

    @Test
    @DisplayName("On a light painting it is the pale parts that take the ink")
    void paleOnLight() {
        Backdrop light = new Backdrop(Key.key("x", "page"), "\uE000", TextColor.color(0x4A3220));
        Component title = light.frame(Component.text("Hi").color(NamedTextColor.WHITE)
                .append(Component.text("!").color(NamedTextColor.DARK_GRAY))).children().getFirst();
        assertThat(title.color()).isEqualTo(TextColor.color(0x4A3220));
        assertThat(title.children().getFirst().color()).isEqualTo(NamedTextColor.DARK_GRAY);
        assertThat(light.frame(Component.text("A").color(TextColor.color(0xC9A0FF))).children().getFirst().color())
                .as("the brand's light purple is too faint on parchment").isEqualTo(TextColor.color(0x4A3220));
    }

    @Test
    @DisplayName("The title font is measured: a trail too wide for the window drops the page it came from")
    void longTrailDropsTheParent() {
        MenuStyle.install(Set.of(WEARING)::contains);
        Menu parent = new Menu(viewer(WEARING), BRAND, null) {
            @Override
            protected Component title() {
                return Component.text("Every adventurer's ledger");
            }

            @Override
            protected void render() {
            }
        };
        Menu child = new Menu(viewer(WEARING), BRAND, parent) {
            @Override
            protected Component title() {
                return Component.text("Choose your class");
            }

            @Override
            protected Backdrop backdrop() {
                return Backdrop.house(6).writtenIn(Backdrop.TITLE_FONT);
            }

            @Override
            protected void render() {
            }
        };
        String shown = plain(child.shownTitle());
        assertThat(TitleFont.width(shown.substring(Backdrop.house(6).glyphs().length())))
                .isLessThanOrEqualTo(TitleFont.BUDGET);
        assertThat(shown).endsWith("Choose your class").doesNotContain("ledger");
        assertThat(child.shownTitle().children().getFirst().font()).isEqualTo(Backdrop.TITLE_FONT);
    }

    @Test
    @DisplayName("The title font's widths come from its own glyphs; what it lacks is measured as the game's font")
    void widths() {
        assertThat(TitleFont.width("A")).isEqualTo(8);
        assertThat(TitleFont.width("i")).isEqualTo(4);
        assertThat(TitleFont.width(" ")).isEqualTo(4);
        assertThat(TitleFont.width("\u2603")).isPositive();
    }

    @Test
    @DisplayName("In the title font a page the brand had to shorten is shown whole, without the brand")
    void clippedPageShownWhole() {
        MenuStyle.install(Set.of(WEARING)::contains);
        Menu page = new Menu(viewer(WEARING), new Brand("Adventurers"), null) {
            @Override
            protected Component title() {
                return Component.text("Choose your class");
            }

            @Override
            protected void render() {
            }

            @Override
            protected Backdrop backdrop() {
                return Backdrop.house(6).writtenIn(Backdrop.TITLE_FONT);
            }
        };
        String shown = plain(page.shownTitle());
        assertThat(shown).endsWith("Choose your class").doesNotContain("Adventurers").doesNotContain("\u2026");
    }

    @Test
    @DisplayName("A gradient is recoloured whole or not at all — half a word in ink and half in purple reads as a fault")
    void gradientsWhole() {
        Backdrop light = new Backdrop(Key.key("x", "page"), "\uE000", TextColor.color(0x4A3220));
        Component brand = Component.text().append(Component.text("A").color(TextColor.color(0xC9A0FF)),
                Component.text("B").color(TextColor.color(0x7C5CBF))).build();
        Component title = light.frame(brand).children().getFirst();
        assertThat(title.children()).allSatisfy(letter -> assertThat(letter.color()).isEqualTo(light.ink()));
    }

    @Test
    @DisplayName("An owner can switch the paintings off for the whole server")
    void serverOff() {
        MenuStyle.install(Set.of(WEARING)::contains, () -> false, id -> true);
        assertThat(new Page(viewer(WEARING), 6).designed()).isFalse();
        MenuStyle.install(Set.of(WEARING)::contains, () -> true, id -> true);
        assertThat(new Page(viewer(WEARING), 6).designed()).isTrue();
    }

    @Test
    @DisplayName("A player can switch them off for themselves only")
    void playerOff() {
        UUID other = UUID.randomUUID();
        MenuStyle.install(Set.of(WEARING, other)::contains, () -> true, id -> !id.equals(WEARING));
        assertThat(new Page(viewer(WEARING), 6).designed()).isFalse();
        assertThat(new Page(viewer(other), 6).designed()).isTrue();
    }

    @Test
    @DisplayName("The player's switch is Core's own, on unless they turn it off")
    void playerSwitch() {
        assertThat(MenuStyle.PAINTED.key().toString()).isEqualTo("rainsswitches:rainscore.painted-menus");
        assertThat(MenuStyle.PAINTED.defaultOn()).isTrue();
    }
}
