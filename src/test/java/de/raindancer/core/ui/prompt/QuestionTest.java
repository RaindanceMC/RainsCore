package de.raindancer.core.ui.prompt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionTest {

    private final UUID player = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final ChatPrompts prompts = new ChatPrompts(clock::get);
    private final List<String> said = new ArrayList<>();

    private boolean ask(Question<?> question) {
        return question.ask(player, prompts,
                line -> said.add(PlainTextComponentSerializer.plainText().serialize(line)), null, null, clock::get);
    }

    @Nested
    @DisplayName("asking")
    class Asking {

        @Test
        @DisplayName("a good answer is read as the value asked for, and nothing more is said")
        void goodAnswer() {
            AtomicReference<Integer> got = new AtomicReference<>();
            ask(Question.asking(Parsers.wholeNumber(1, 64)).prompt("How many?").onAnswer(got::set));

            prompts.offer(player, " 16 ");

            assertThat(got).hasValue(16);
            assertThat(said).first().isEqualTo("How many?");
        }

        @Test
        @DisplayName("a wrong answer is refused with what would work, and asked again")
        void wrongThenRight() {
            AtomicReference<Integer> got = new AtomicReference<>();
            ask(Question.asking(Parsers.wholeNumber(1, 64)).prompt("How many?").onAnswer(got::set));

            prompts.offer(player, "lots");
            assertThat(said).anySatisfy(line -> assertThat(line)
                    .contains("That is not a whole number from 1 to 64.").contains("2 tries left"));
            assertThat(prompts.isWaiting(player)).as("asked again").isTrue();

            prompts.offer(player, "70");
            prompts.offer(player, "64");
            assertThat(got).hasValue(64);
        }

        @Test
        @DisplayName("it gives up after the last try, and says nothing was changed")
        void givesUp() {
            AtomicInteger cancelled = new AtomicInteger();
            ask(Question.asking(Parsers.yesNo()).attempts(2).onCancel(cancelled::incrementAndGet));

            prompts.offer(player, "maybe");
            prompts.offer(player, "perhaps");

            assertThat(cancelled).hasValue(1);
            assertThat(said.getLast()).contains("last try").contains("nothing was changed");
            assertThat(prompts.isWaiting(player)).isFalse();
        }

        @Test
        @DisplayName("cancel stops it, and so does running out of time — each told apart")
        void cancelAndTimeout() {
            AtomicInteger cancelled = new AtomicInteger();
            ask(Question.asking(Parsers.text(10)).onCancel(cancelled::incrementAndGet));
            prompts.offer(player, "cancel");
            assertThat(said.getLast()).startsWith("Cancelled");

            ask(Question.asking(Parsers.text(10)).within(Duration.ofSeconds(30)).onCancel(cancelled::incrementAndGet));
            clock.addAndGet(31_000);
            prompts.sweep();
            assertThat(said.getLast()).startsWith("No answer came");
            assertThat(cancelled).hasValue(2);
        }

        @Test
        @DisplayName("suggestions are offered; something else already asking is said, not overridden")
        void suggestionsAndBusy() {
            ask(Question.asking(Parsers.oneOf(List.of("easy", "hard"))).suggest("easy", "hard"));
            assertThat(said).anySatisfy(line -> assertThat(line).contains("[easy]").contains("[hard]"));

            assertThat(ask(Question.asking(Parsers.text(5)))).isFalse();
            assertThat(said.getLast()).contains("already waiting for your answer");
        }

        @Test
        @DisplayName("markup typed as an answer is refused as a value, never acted on")
        void markupIsJustWrong() {
            AtomicReference<String> got = new AtomicReference<>();
            ask(Question.asking(Parsers.name(8)).onAnswer(got::set));
            prompts.offer(player, "<click:run_command:'/op x'>a");

            assertThat(got).hasValue(null);
            assertThat(said).anySatisfy(line -> assertThat(line).contains("A name of up to 8 letters"));
        }
    }

    @Nested
    @DisplayName("parsers")
    class ParsersSay {

        @Test
        @DisplayName("numbers, decimals with a comma, yes/no in two languages")
        void numbers() {
            assertThat(Parsers.number(0, 10).parse("2,5").value()).isEqualTo(2.5);
            assertThat(Parsers.number(0, 10).parse("11").problem()).isEqualTo("Not in range: a number from 0 to 10.");
            assertThat(Parsers.yesNo().parse("Ja").value()).isTrue();
            assertThat(Parsers.yesNo().parse("off").value()).isFalse();
        }

        @Test
        @DisplayName("lengths of time, with both bounds or one")
        void durations() {
            assertThat(Parsers.duration(Duration.ofMinutes(1), Duration.ofHours(1)).parse("30m").value())
                    .isEqualTo(Duration.ofMinutes(30));
            assertThat(Parsers.duration(Duration.ofMinutes(1), null).parse("10s").problem()).startsWith("At least");
            assertThat(Parsers.duration(null, null).parse("soon").isOk()).isFalse();
        }

        @Test
        @DisplayName("one of several, by any case or a unique start; an ambiguous start asks which")
        void choices() {
            Parsers.Parser<String> blocks = Parsers.oneOf(List.of("Diamond", "Dirt", "Gold"));

            assertThat(blocks.parse("gold").value()).isEqualTo("Gold");
            assertThat(blocks.parse("dia").value()).isEqualTo("Diamond");
            assertThat(blocks.parse("di").problem()).isEqualTo("Which one: Diamond, Dirt?");
            assertThat(blocks.parse("x").problem()).isEqualTo("One of: Diamond, Dirt, Gold.");
        }

        @Test
        @DisplayName("text and names have limits, and a rule can be added")
        void textAndNames() {
            assertThat(Parsers.text(3).parse("abcd").problem()).isEqualTo("At most 3 characters; that was 4.");
            assertThat(Parsers.name(16).parse("my home").isOk()).isFalse();
            assertThat(Parsers.name(16).andCheck(name -> !name.equals("spawn"), "That name is taken.")
                    .parse("spawn").problem()).isEqualTo("That name is taken.");
            assertThat(Parsers.wholeNumber(1, 5).map(Integer::toBinaryString).parse("5").value()).isEqualTo("101");
        }
    }

    @Test
    @DisplayName("every line it can say is in messages.yml, so an owner can reword it")
    void wordingIsDefined() throws Exception {
        String file = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/messages.yml"));
        for (String key : List.of("how:", "again:", "gave-up:", "timed-out:", "cancelled:", "busy:", "suggestions:")) {
            assertThat(file).contains("  " + key);
        }
        assertThat(Component.empty()).isNotNull();
    }
}
