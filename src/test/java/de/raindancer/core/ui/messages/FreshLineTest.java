package de.raindancer.core.ui.messages;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A list of jokes picked at random repeats one within a handful of tries — the birthday problem makes
 * sixty lines feel like six. {@link Messages#fresh} deals the whole list before it repeats anything.
 */
class FreshLineTest {

    private static Messages with(String yaml) {
        Messages messages = new Messages(null);
        messages.load(new ByteArrayInputStream(("prefix: \"[P] \"\n" + yaml).getBytes(StandardCharsets.UTF_8)));
        return messages;
    }

    private static String plain(net.kyori.adventure.text.Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line);
    }

    @Test
    @DisplayName("every line is said once before any is said twice, and the next round starts over")
    void dealsTheWholeList() {
        Messages messages = with("""
                fun:
                  jokes: ["one", "two", "three", "four", "five"]
                """);
        Set<String> firstRound = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            firstRound.add(plain(messages.fresh("fun.jokes")));
        }
        assertThat(firstRound).containsExactlyInAnyOrder("one", "two", "three", "four", "five");
        Set<String> secondRound = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            secondRound.add(plain(messages.fresh("fun.jokes")));
        }
        assertThat(secondRound).hasSize(5);
    }

    @Test
    @DisplayName("the last line of one round is never the first of the next")
    void noRepeatAcrossRounds() {
        Messages messages = with("""
                fun:
                  jokes: ["a", "b", "c"]
                """);
        List<String> said = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            said.add(plain(messages.fresh("fun.jokes")));
        }
        for (int i = 1; i < said.size(); i++) {
            assertThat(said.get(i)).as("line %d", i).isNotEqualTo(said.get(i - 1));
        }
    }

    @Test
    @DisplayName("placeholders are filled and no prefix is added: the caller decides what goes in front")
    void fillsWithoutPrefix() {
        Messages messages = with("""
                fun:
                  roasts: ["<target>, you are a pickaxe in a world of shovels."]
                """);
        assertThat(plain(messages.fresh("fun.roasts", "target", "Bo")))
                .isEqualTo("Bo, you are a pickaxe in a world of shovels.");
    }

    @Test
    @DisplayName("a single line, or a plain key, behaves like get")
    void singleLine() {
        Messages messages = with("""
                fun:
                  one: "only"
                """);
        assertThat(plain(messages.fresh("fun.one"))).isEqualTo("only");
        assertThat(plain(messages.fresh("fun.missing"))).isEqualTo(plain(messages.get("fun.missing")));
    }

    @Test
    @DisplayName("a list that changed size after a reload starts a new round rather than breaking")
    void followsTheList() {
        Messages messages = with("""
                fun:
                  jokes: ["a", "b"]
                """);
        messages.fresh("fun.jokes");
        messages.force("fun.jokes", List.of("x", "y", "z"));
        Set<String> said = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            said.add(plain(messages.fresh("fun.jokes")));
        }
        assertThat(said).containsExactlyInAnyOrder("x", "y", "z");
    }
}
