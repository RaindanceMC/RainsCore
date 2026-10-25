package de.raindancer.core.ui.hologram;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Screens: where they stand, which one is meant, who sees which")
class ScreenRuleTest {

    private final ScreenRule rule = new ScreenRule();

    @Test
    @DisplayName("a placed screen is one line — number, world, position, kind — read back the same")
    void roundTrip() {
        PlacedScreen screen = new PlacedScreen(3, "lobby", 10.25, 66.2, -4.5, "balance");
        assertThat(screen.line()).isEqualTo("3 lobby 10.25 66.20 -4.50 balance");
        assertThat(PlacedScreen.parse(screen.line())).contains(screen);
    }

    @Test
    @DisplayName("garbled lines are left out")
    void garbled() {
        assertThat(PlacedScreen.parse("x lobby 1 2 3 balance")).isEmpty();
        assertThat(PlacedScreen.parse("1 lobby 1 2 3")).isEmpty();
        assertThat(PlacedScreen.parse("1 lobby 1 two 3 balance")).isEmpty();
        assertThat(PlacedScreen.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("moved up or down, everything else stays")
    void moved() {
        PlacedScreen screen = new PlacedScreen(3, "lobby", 1, 64, 1, "online");
        assertThat(screen.raised(0.5).y()).isEqualTo(64.5);
        assertThat(screen.raised(-1).y()).isEqualTo(63);
        assertThat(screen.raised(0.5).number()).isEqualTo(3);
        assertThat(screen.at("lobby", 5, 70, 6)).isEqualTo(new PlacedScreen(3, "lobby", 5, 70, 6, "online"));
    }

    @Test
    @DisplayName("the next number is one past the highest, so removing one never makes two share a number")
    void numbers() {
        assertThat(rule.nextNumber(List.of())).isEqualTo(1);
        assertThat(rule.nextNumber(List.of(new PlacedScreen(1, "w", 0, 0, 0, "a"),
                new PlacedScreen(7, "w", 0, 0, 0, "a")))).isEqualTo(8);
    }

    @Test
    @DisplayName("the screen meant is the nearest within reach, in the same world")
    void nearest() {
        PlacedScreen near = new PlacedScreen(1, "w", 2, 64, 0, "a");
        PlacedScreen far = new PlacedScreen(2, "w", 20, 64, 0, "a");
        PlacedScreen elsewhere = new PlacedScreen(3, "nether", 0, 64, 0, "a");
        List<PlacedScreen> all = List.of(far, near, elsewhere);
        assertThat(rule.nearest(all, "w", 0, 64, 0, 5)).contains(near);
        assertThat(rule.nearest(all, "w", 30, 64, 0, 5)).isEmpty();
        assertThat(rule.nearest(all, "nether", 1, 64, 0, 5)).contains(elsewhere);
    }

    @Test
    @DisplayName("how far a nudge goes: a quarter block unless told, never silly")
    void nudges() {
        assertThat(rule.nudge(null)).isEqualTo(0.25);
        assertThat(rule.nudge("0.5")).isEqualTo(0.5);
        assertThat(rule.nudge("1,5")).isEqualTo(1.5);
        assertThat(rule.nudge("-2")).isEqualTo(2);
        assertThat(rule.nudge("500")).isEqualTo(ScreenRule.MOST_NUDGE);
        assertThat(rule.nudge("0")).isEqualTo(ScreenRule.LEAST_NUDGE);
        assertThat(rule.nudge("lots")).isNull();
    }

    @Test
    @DisplayName("who gets a display of a personal screen and whose goes")
    void viewers() {
        UUID ada = UUID.randomUUID();
        UUID bo = UUID.randomUUID();
        UUID cy = UUID.randomUUID();
        ScreenRule.Viewers change = rule.viewers(Set.of(ada, bo), Set.of(bo, cy));
        assertThat(change.add()).containsExactly(cy);
        assertThat(change.remove()).containsExactly(ada);
    }

    @Test
    @DisplayName("a kind's name is short and plain, so it can be typed and written to a line")
    void kindNames() {
        ScreenKind kind = ScreenKind.everyone("online", "Online", "Who is on", Material.PLAYER_HEAD,
                () -> Component.text("x"));
        assertThat(kind.personal()).isFalse();
        assertThat(ScreenKind.personal("you", "You", "Your stats", Material.BOOK, viewer -> Component.empty())
                .personal()).isTrue();
        assertThatThrownBy(() -> ScreenKind.everyone("Two Words", "x", "x", Material.STONE, Component::empty))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a player's screen carries its owner on its line; staff screens have none, as before")
    void owners() {
        UUID ada = UUID.randomUUID();
        PlacedScreen own = new PlacedScreen(4, "w", 1, 64, 1, "balance", ada);
        assertThat(own.line()).endsWith(" balance " + ada);
        assertThat(PlacedScreen.parse(own.line())).contains(own);
        assertThat(PlacedScreen.parse("3 lobby 1.00 2.00 3.00 online")).get()
                .extracting(PlacedScreen::owner).isNull();
        assertThat(own.raised(1).owner()).isEqualTo(ada);
        assertThat(own.at("x", 0, 0, 0).owner()).isEqualTo(ada);
        assertThat(PlacedScreen.parse("3 lobby 1 2 3 online not-a-uuid")).isEmpty();
    }

    @Test
    @DisplayName("players touch only their own screens; staff touch every one")
    void touching() {
        UUID ada = UUID.randomUUID();
        UUID bo = UUID.randomUUID();
        PlacedScreen adas = new PlacedScreen(1, "w", 0, 64, 0, "balance", ada);
        PlacedScreen staffs = new PlacedScreen(2, "w", 0, 64, 0, "online");
        assertThat(rule.mayTouch(adas, ada, false)).isTrue();
        assertThat(rule.mayTouch(adas, bo, false)).isFalse();
        assertThat(rule.mayTouch(staffs, ada, false)).isFalse();
        assertThat(rule.mayTouch(adas, bo, true)).isTrue();
        assertThat(rule.mine(List.of(adas, staffs), ada)).containsExactly(adas);
    }

    @Test
    @DisplayName("a player may put one up while the switch is on, the kind is allowed and they are under their limit")
    void placing() {
        assertThat(rule.mayPlace(true, false, 2, 3, "balance", Set.of())).isNull();
        assertThat(rule.mayPlace(true, false, 3, 3, "balance", Set.of())).isEqualTo(ScreenRule.Refusal.LIMIT);
        assertThat(rule.mayPlace(false, false, 0, 3, "balance", Set.of())).isEqualTo(ScreenRule.Refusal.OFF);
        assertThat(rule.mayPlace(true, false, 0, 3, "online", Set.of("online")))
                .isEqualTo(ScreenRule.Refusal.KIND);
        assertThat(rule.mayPlace(false, true, 99, 3, "online", Set.of("online"))).as("staff always").isNull();
    }

    @Test
    @DisplayName("the kinds players may not put up, read from a comma list")
    void excluded() {
        assertThat(rule.kinds(" online, Moderation-Staff ,,")).containsExactlyInAnyOrder("online", "moderation-staff");
        assertThat(rule.kinds("")).isEmpty();
        assertThat(rule.kinds(null)).isEmpty();
    }
}
