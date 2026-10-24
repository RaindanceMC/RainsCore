package de.raindancer.core.ui.help;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HelpTextTest {

    private final HelpContexts.Fixed context = HelpContexts.fixed(Map.of(
                    "economy:pay.tax-percent", 5.0,
                    "economy:features.pay", true,
                    "economy:accounts.starting-balance", "1000",
                    "economy:pay.cooldown-seconds", 90,
                    "claims:name", "<red>sneaky",
                    "warp:worlds", List.of("world", "nether")))
            .withFacts(Map.of("economy:richest", "Tom"))
            .withTopics(Map.of("shop", "The Shop"));

    private String plain(String text) {
        return HelpText.plain(HelpText.render(text, context, new ArrayList<>()));
    }

    @Test
    @DisplayName("settings are read live and formatted as asked")
    void settings() {
        assertThat(plain("Tax: {setting:economy:pay.tax-percent|percent}.")).isEqualTo("Tax: 5 %.");
        assertThat(plain("Wait {setting:economy:pay.cooldown-seconds|seconds}.")).isEqualTo("Wait 1 minute 30 seconds.");
        assertThat(plain("Paying is {setting:economy:features.pay}.")).isEqualTo("Paying is on.");
        assertThat(plain("Paying: {setting:economy:features.pay|yesno}.")).isEqualTo("Paying: yes.");
        assertThat(plain("Worlds: {setting:warp:worlds}, {setting:warp:worlds|count} of them."))
                .isEqualTo("Worlds: world, nether, 2 of them.");
        assertThat(plain("Start with {setting:economy:accounts.starting-balance|money}.")).isEqualTo("Start with ⛃1,000.");
        assertThat(plain("{money:250} or so")).isEqualTo("⛃250 or so");
    }

    @Test
    @DisplayName("a value is shown as the characters it is, never as markup")
    void escaped() {
        assertThat(plain("Name: {setting:claims:name}")).isEqualTo("Name: <red>sneaky");
    }

    @Test
    @DisplayName("an unknown setting is a problem and a question mark, not a crash")
    void unknown() {
        List<String> problems = new ArrayList<>();
        Component rendered = HelpText.render("It costs {setting:economy:nope}.", context, problems);
        assertThat(HelpText.plain(rendered)).isEqualTo("It costs ?.");
        assertThat(problems).singleElement().asString().contains("economy:nope");
    }

    @Test
    @DisplayName("inline conditions pick a branch")
    void inlineIf() {
        assertThat(plain("Paying {if:economy:pay.tax-percent > 0}costs {setting:economy:pay.tax-percent|percent}"
                + "{else}is free{/if}.")).isEqualTo("Paying costs 5 %.");
        assertThat(plain("Paying {if:economy:pay.tax-percent > 10}costs a lot{else}is cheap{/if}."))
                .isEqualTo("Paying is cheap.");
        assertThat(plain("A{if:!economy:features.pay} B{/if} C")).isEqualTo("A C");
    }

    @Test
    @DisplayName("an unclosed or nested if is a problem")
    void brokenIf() {
        List<String> problems = new ArrayList<>();
        HelpText.render("{if:a:b}never closed", context, problems);
        assertThat(problems).isNotEmpty();
        problems.clear();
        HelpText.render("{if:a:b}{if:c:d}x{/if}{/if}", context, problems);
        assertThat(problems).isNotEmpty();
    }

    @Test
    @DisplayName("commands become clickable, topics become links, facts and names fill in")
    void links() {
        Component rendered = HelpText.render("Type {cmd:/pay <player> <amount>} or read {topic:shop}. "
                + "Richest: {fact:economy:richest}. Hi {player} on {server}.", context, new ArrayList<>());
        assertThat(HelpText.plain(rendered))
                .isEqualTo("Type /pay <player> <amount> or read The Shop. Richest: Tom. Hi Reader on This server.");
        List<ClickEvent> clicks = new ArrayList<>();
        collect(rendered, clicks);
        assertThat(clicks).extracting(ClickEvent::action)
                .contains(ClickEvent.Action.SUGGEST_COMMAND, ClickEvent.Action.RUN_COMMAND);
        assertThat(clicks.getFirst().payload().toString()).contains("/pay ");
    }

    @Test
    @DisplayName("double braces write a brace; markup written by the author works")
    void escapesAndMarkup() {
        assertThat(plain("Use {{braces}} and <gold>gold</gold>")).isEqualTo("Use {braces} and gold");
    }

    @Test
    @DisplayName("the placeholders a text reads are listed, for the lint")
    void reads() {
        assertThat(HelpText.settingsRead("{setting:a:b} {setting:c:d|money} {if:e:f > 1}x{/if}"))
                .containsExactly("a:b", "c:d", "e:f");
    }

    private static void collect(Component component, List<ClickEvent> into) {
        if (component.clickEvent() != null) {
            into.add(component.clickEvent());
        }
        component.children().forEach(child -> collect(child, into));
    }
}
