package de.raindancer.core.ui.menu;

import de.raindancer.core.ui.chat.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The window title saying where you are, not just what you are looking at.
 *
 * <h2>Why bother</h2>
 * A chest menu has no other chrome. There is a Back button, but nothing that says what Back goes back to, so
 * three levels into a plugin the title read "Trusted people" and nothing on screen said which claim that
 * belonged to. With the page it was opened from in front of it, the title is the window's only orientation:
 *
 * <pre>
 * RSC » Server › All claims
 * </pre>
 *
 * <h2>The page wins the budget</h2>
 * A title gets 154 pixels and Minecraft clips it by cutting the <em>end</em> off, which is the worst possible
 * end to lose: joining the names naively produced {@code Claims » claimtrials › Trusted…} — the space went on
 * where you came from and where you are was cut off mid-word.
 *
 * <p>So the parent is included only when both names fit whole, and dropped when they do not. That is the whole
 * contract, and it is why two short names get a trail and two long ones do not. A title that says less beats
 * one that trails off, and the alternative — abbreviating the parent — produces titles nobody can read either.
 *
 * <p>Only ever the immediate parent, never the chain: three names never fit, so a chain would mean the trail
 * silently switched itself off on every deep page.
 */
class MenuTrailTest {

    private static final Brand BRAND = new Brand("Claims");

    /** A menu that does nothing but have a name and a parent, which is all the trail is about. */
    private static final class Page extends Menu {

        private final String name;

        private Page(String name, Menu parent) {
            super(null, BRAND, parent);
            this.name = name;
        }

        @Override
        protected Component title() {
            return Component.text(name);
        }

        @Override
        protected void render() {
        }
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("a front page is just itself")
    void aRootPageHasNoTrail() {
        assertThat(plain(new Page("Your claims", null).windowTitle()))
                .contains("Your claims")
                .doesNotContain("›");
    }

    @Test
    @DisplayName("two names that fit both appear, in the order you walked them")
    void achildCarriesItsParent() {
        // The shape from the screenshot this was modelled on: RSC » Server › All claims.
        Page parent = new Page("Server", null);
        String title = plain(new Page("All claims", parent).windowTitle());

        assertThat(title)
                .as("without this, nothing on screen says what Back goes back to")
                .contains("Server")
                .contains("All claims")
                .contains("\u203a");
        assertThat(title.indexOf("Server")).isLessThan(title.indexOf("All claims"));
    }

    @Test
    @DisplayName("two names that do not fit lose the parent, not the page")
    void thePageIsNeverTheOneClipped() {
        // Long enough that both names cannot share the 146 pixels, now that no tag spends any of them.
        Page parent = new Page("The Grand Duchy of Claimtrials", null);
        String title = plain(new Page("Trusted people", parent).windowTitle());

        assertThat(title)
                .as("the page is where you ARE; clipping it mid-word is the one outcome worth avoiding")
                .contains("Trusted people")
                .doesNotContain("\u2026");
        assertThat(title)
                .as("dropped whole rather than abbreviated to something nobody can read")
                .doesNotContain("Claimtrials");
    }

    @Test
    @DisplayName("the trail is one level, never the whole chain")
    void theTrailStopsAtOne() {
        Page grandparent = new Page("A", null);
        Page parent = new Page("B", grandparent);

        assertThat(plain(new Page("C", parent).windowTitle()))
                .as("three names never fit, so a chain would switch the trail off on every deep page")
                .contains("B")
                .contains("C")
                .doesNotContain("A \u203a");
    }

    @Test
    @DisplayName("the brand no longer stands in front of it — the title is the pages' alone")
    void theBrandStaysOutOfTheTitle() {
        Page parent = new Page("Server", null);
        String title = plain(new Page("Fence", parent).windowTitle());

        assertThat(title).startsWith("Server").doesNotContain("Claims").doesNotContain("»");
        assertThat(title.indexOf("Server")).isLessThan(title.indexOf("Fence"));
    }

    @Test
    @DisplayName("a player-supplied name is never read as markup")
    void anameIsNotParsedAsColour() {
        // A claim called "<red>" must not colour the rest of the title, and a claim called
        // "<click:run_command:/op me>" must not become a click event in a window title. This is the reason
        // the trail is built from Components rather than by concatenating MiniMessage.
        Page parent = new Page("<red>", null);

        assertThat(plain(new Page("Fence", parent).windowTitle()))
                .contains("<red>")
                .contains("Fence");
    }

    @Test
    @DisplayName("a parent whose subject has gone does not take the child page down with it")
    void abrokenParentTitleIsSurvivable() {
        // Found by review. The trail asks the parent for its title every time the child's window is built,
        // including on a refresh — and a parent page's title is usually built from the thing it is about.
        // Open a claim's member list, have the claim deleted underneath you, click anything, and the parent's
        // title() now throws while the CHILD is the page being drawn.
        //
        // Before the trail, a child was insulated from its parent's state once opened. It has to stay that
        // way: losing the breadcrumb is a cosmetic loss, and losing the window is not.
        Menu parent = new Menu(null, BRAND, null) {
            @Override
            protected Component title() {
                throw new IllegalStateException("the claim this page was about is gone");
            }

            @Override
            protected void render() {
            }
        };

        assertThat(plain(new Page("Trusted people", parent).windowTitle()))
                .as("the child page still has a title of its own, and that is the one that matters")
                .contains("Trusted people");
    }

    @Test
    @DisplayName("a long page name stays inside the window frame, with room to spare")
    void alongTitleDoesNotTouchTheEdge() {
        // From a screenshot: "Claims » Where nobody may cl…" — the clip had run, the ellipsis was there, and the
        // text still reached the right-hand edge of the window. So the budget was not wrong about clipping, it
        // was wrong about how much room a chest title actually has.
        //
        // The frame is 176 pixels wide and the title is drawn eight in from the left, which leaves 160 before it
        // meets the far edge. Filling all 160 is what produced a title touching the border, so the budget keeps
        // a margin and this test is the thing that stops somebody widening it back.
        String rendered = plain(new Page("Where nobody may claim", null).windowTitle());

        assertThat(de.raindancer.core.platform.util.FontWidth.of(rendered))
                .as("a title that reaches the frame reads as a rendering fault, whether it was clipped or not")
                .isLessThanOrEqualTo(150);
    }

    @Test
    @DisplayName("the margin is not so generous that ordinary names get cut")
    void ashortTitleIsLeftWhole() {
        // The other half: a budget tightened too far starts clipping names nobody would call long, and the
        // ellipsis then looks like a bug in a different place.
        for (String page : java.util.List.of("Greetings", "Fence", "People", "Configuration", "The manual")) {
            assertThat(plain(new Page(page, null).windowTitle()))
                    .as("'" + page + "' is a name that has to survive whole")
                    .contains(page)
                    .doesNotContain("\u2026");
        }
    }
}
