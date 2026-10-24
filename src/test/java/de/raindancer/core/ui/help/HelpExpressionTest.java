package de.raindancer.core.ui.help;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HelpExpressionTest {

    private final HelpContext context = HelpContexts.fixed(Map.of(
                    "economy:features.pay", true,
                    "economy:pay.tax-percent", 5.0,
                    "economy:bills.minutes", 0,
                    "claims:mode", "PERSONAL",
                    "warp:worlds", List.of("world", "nether"),
                    "warp:blocked", List.of()))
            .withPermissions(Set.of("rainseconomy.admin"))
            .withPlugins(Set.of("RainsShops"))
            .withModules(Set.of("shops"));

    private boolean eval(String expression) {
        return HelpExpression.parse(expression).test(context);
    }

    @Test
    @DisplayName("a setting on its own is true when it is on, non-zero, or non-empty")
    void truthiness() {
        assertThat(eval("economy:features.pay")).isTrue();
        assertThat(eval("economy:pay.tax-percent")).isTrue();
        assertThat(eval("economy:bills.minutes")).isFalse();
        assertThat(eval("warp:worlds")).isTrue();
        assertThat(eval("warp:blocked")).isFalse();
        assertThat(eval("nobody:declared.this")).isFalse();
    }

    @Test
    @DisplayName("comparisons read numbers as numbers and everything else case-blind")
    void comparisons() {
        assertThat(eval("economy:pay.tax-percent > 4")).isTrue();
        assertThat(eval("economy:pay.tax-percent >= 5")).isTrue();
        assertThat(eval("economy:pay.tax-percent < 5")).isFalse();
        assertThat(eval("economy:pay.tax-percent == 5")).isTrue();
        assertThat(eval("claims:mode == personal")).isTrue();
        assertThat(eval("claims:mode != 'guild'")).isTrue();
        assertThat(eval("economy:features.pay == true")).isTrue();
    }

    @Test
    @DisplayName("! binds tighter than &&, which binds tighter than ||")
    void precedence() {
        assertThat(eval("!economy:bills.minutes")).isTrue();
        assertThat(eval("economy:bills.minutes || economy:features.pay && claims:mode == personal")).isTrue();
        assertThat(eval("economy:bills.minutes && economy:features.pay || economy:bills.minutes")).isFalse();
    }

    @Test
    @DisplayName("permissions, plugins and modules can be asked about too")
    void specials() {
        assertThat(eval("@perm:rainseconomy.admin")).isTrue();
        assertThat(eval("@perm:rainseconomy.other")).isFalse();
        assertThat(eval("@plugin:rainsshops")).isTrue();
        assertThat(eval("@module:shops && !@module:casino")).isTrue();
    }

    @Test
    @DisplayName("broken expressions are refused when read, not when somebody opens the page")
    void broken() {
        assertThatThrownBy(() -> HelpExpression.parse("economy:features.pay &&"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HelpExpression.parse("a == "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HelpExpression.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HelpExpression.parse("@nonsense:x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an expression knows which settings it reads, so a lint can check them")
    void readsSettings() {
        assertThat(HelpExpression.parse("a:b && !c:d || @perm:x").settings()).containsExactly("a:b", "c:d");
    }
}
