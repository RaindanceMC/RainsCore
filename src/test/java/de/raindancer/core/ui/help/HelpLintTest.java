package de.raindancer.core.ui.help;

import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class HelpLintTest {

    @Settings(id = "economy", topics = @Topic(path = "economy", title = "Economy"))
    public record Money(@In("economy") @Title("Tax") @Key("pay.tax-percent") double tax,
                 @In("economy") @Title("Paying") @Key("features.pay") boolean pay) {
        static final Money DEFAULTS = new Money(5, true);
    }

    @TempDir
    Path folder;

    private void write(String name, String yaml) throws IOException {
        Files.writeString(folder.resolve(name), yaml);
    }

    private HelpLint lint() {
        return HelpLint.of(folder).module("economy").settings(Money.class, Money.DEFAULTS)
                .commands(List.of("pay", "balance", "rcclick")).undocumented("rcclick");
    }

    @Test
    @DisplayName("a complete guide has no problems")
    void clean() throws IOException {
        write("economy.yml", """
                id: economy
                title: Economy
                summary: Money.
                module: economy
                commands: [pay, balance]
                sections:
                  - question: Is paying taxed?
                    text: "{if:economy:pay.tax-percent > 0}Yes, {setting:economy:pay.tax-percent|percent}{else}No{/if}."
                    when: economy:features.pay
                """);
        assertThat(lint().problems()).isEmpty();
    }

    @Test
    @DisplayName("typos in keys, bare keys, missing commands, a wrong module and a misnamed file are each found")
    void problems() throws IOException {
        write("money.yml", """
                id: economy
                title: Economy
                summary: Money.
                module: eco
                commands: [pay, fly]
                related: [shop]
                sections:
                  - question: Tax?
                    text: "{setting:economy:pay.tax} and {setting:features.pay} and {fact:nobody}"
                    when: economy:features.paying
                """);
        List<String> problems = lint().problems();
        assertThat(problems).anyMatch(p -> p.contains("economy.yml"))        // file name
                .anyMatch(p -> p.contains("module: economy"))
                .anyMatch(p -> p.contains("/fly"))
                .anyMatch(p -> p.contains("/balance is in no topic"))
                .anyMatch(p -> p.contains("'shop'"))
                .anyMatch(p -> p.contains("'economy:pay.tax' does not exist"))
                .anyMatch(p -> p.contains("'features.pay' — write settings as plugin:key"))
                .anyMatch(p -> p.contains("'economy:features.paying' does not exist"))
                .anyMatch(p -> p.contains("fact 'nobody'"));
    }

    @Test
    @DisplayName("another plugin's settings and topics are taken on trust when declared")
    void foreign() throws IOException {
        write("economy.yml", """
                id: economy
                title: Economy
                summary: Money.
                module: economy
                commands: [pay, balance]
                related: [shop]
                sections:
                  - question: Claims?
                    text: "A claim costs {setting:claims:price|money}; see {topic:shop}."
                """);
        assertThat(lint().foreignSettings("claims").otherTopics("shop").problems()).isEmpty();
    }
}
