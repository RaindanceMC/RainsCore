package de.raindancer.core.ui.help;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HelpTopicParserTest {

    static final String ECONOMY = """
            id: economy
            title: Economy
            summary: Money here — earning it, keeping it, sending it.
            intro: Ah, money. Everybody's favourite topic.
            icon: GOLD_INGOT
            category: economy & TRADE
            order: 10
            aliases: [Money, eco]
            keywords: [coins]
            module: economy
            commands: [/balance, pay]
            related: [shop]
            sections:
              - question: How do I earn money?
                title: Earning
                text: Sell things with {cmd:/sell}.
              - question: Is paying taxed?
                text: "Yes: {setting:economy:pay.tax-percent|percent}."
                when: economy:pay.tax-percent > 0
              - question: Staff tools?
                text: /eco admin.
                permission: rainseconomy.admin
            """;

    @Test
    @DisplayName("a complete topic reads without a single problem")
    void complete() {
        var parsed = HelpTopicParser.parse(ECONOMY, "test");
        assertThat(parsed.problems()).isEmpty();
        HelpTopic topic = parsed.topic().orElseThrow();
        assertThat(topic.id()).isEqualTo("economy");
        assertThat(topic.category()).isEqualTo("Economy & trade");
        assertThat(topic.aliases()).containsExactly("money", "eco");
        assertThat(topic.commands()).containsExactly("balance", "pay");
        assertThat(topic.opening()).startsWith("Ah, money");
        assertThat(topic.sections()).hasSize(3);
        assertThat(topic.sections().get(0).title()).isEqualTo("Earning");
        assertThat(topic.sections().get(1).title()).isEqualTo("Is paying taxed?");
        assertThat(topic.sections().get(1).when()).isNotNull();
        assertThat(topic.sections().get(2).permission()).isEqualTo("rainseconomy.admin");
    }

    @Test
    @DisplayName("typos in field names are problems, not silently empty topics")
    void typos() {
        var parsed = HelpTopicParser.parse("""
                id: x
                title: X
                summary: An x.
                sectoins:
                  - question: Q?
                    txt: A.
                """, "test");
        assertThat(parsed.problems()).anyMatch(problem -> problem.contains("sectoins"));
        assertThat(parsed.problems()).anyMatch(problem -> problem.contains("no sections"));
    }

    @Test
    @DisplayName("no id, a bad icon, a broken condition and an unanswered question are each said")
    void broken() {
        assertThat(HelpTopicParser.parse("title: X", "t").topic()).isEmpty();
        var parsed = HelpTopicParser.parse("""
                id: x
                title: X
                summary: An x.
                icon: NOT_AN_ITEM
                sections:
                  - question: Q?
                  - question: R?
                    text: Fine.
                    when: "a:b &&"
                """, "t");
        assertThat(parsed.problems()).hasSize(3);
        // The broken condition hides its section rather than showing it by accident.
        HelpTopic topic = parsed.topic().orElseThrow();
        assertThat(topic.sections()).singleElement()
                .satisfies(section -> assertThat(section.when().test(HelpContexts.fixed(java.util.Map.of()))).isFalse());
    }

    @Test
    @DisplayName("not YAML at all is one clear problem")
    void notYaml() {
        var parsed = HelpTopicParser.parse("id: [unclosed", "t");
        assertThat(parsed.topic()).isEmpty();
        assertThat(parsed.problems()).singleElement().asString().contains("not valid YAML");
    }
}
