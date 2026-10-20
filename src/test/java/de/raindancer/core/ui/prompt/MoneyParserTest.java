package de.raindancer.core.ui.prompt;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("An amount of money, typed")
class MoneyParserTest {

    private final Currency coins = Currency.DEFAULT;

    @Test
    @DisplayName("read through the currency: plain, decimals, a k")
    void reads() {
        Parsers.Parser<Money> money = Parsers.money(coins, false, Money.ZERO);
        assertThat(money.parse("12").value()).isEqualTo(coins.ofMajor(12));
        assertThat(money.parse(" 1.5k ").value()).isEqualTo(coins.ofMajor(1500));
        assertThat(money.parse("12.50").value()).isEqualTo(Money.of(1250));
    }

    @Test
    @DisplayName("not an amount, nothing, zero when zero is not wanted: refused with what would work")
    void refuses() {
        Parsers.Parser<Money> money = Parsers.money(coins, false, Money.ZERO);
        assertThat(money.parse("lots").problem()).contains("12").contains("1.5k");
        assertThat(money.parse("").isOk()).isFalse();
        assertThat(money.parse("0").isOk()).isFalse();
        assertThat(money.parse("-5").isOk()).isFalse();
    }

    @Test
    @DisplayName("zero when it means \"none\"; above the most, refused naming the most")
    void zeroAndMost() {
        Parsers.Parser<Money> money = Parsers.money(coins, true, coins.ofMajor(100));
        assertThat(money.parse("0").value()).isEqualTo(Money.ZERO);
        assertThat(money.parse("100").isOk()).isTrue();
        assertThat(money.parse("101").problem()).contains(coins.format(coins.ofMajor(100)));
    }
}
