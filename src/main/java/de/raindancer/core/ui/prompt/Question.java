package de.raindancer.core.ui.prompt;

import de.raindancer.core.RainsCore;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.prompt.Parsers.Parser;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * A question typed into chat, answered properly: what was typed is read as the value asked for, a wrong
 * answer is refused with what would work and asked again, {@code cancel} stops it, it gives up after a
 * while, and the likely answers are clickable.
 *
 * <pre>{@code
 * Question.asking(Parsers.wholeNumber(1, 64))
 *         .prompt("How many should the shop sell at once?")
 *         .suggest("1", "16", "64")
 *         .onAnswer(amount -> shop.setBatch(amount))
 *         .ask(player);
 * }</pre>
 *
 * <p>Built on {@link ChatPrompts}, so a player is only ever asked one thing at a time, the answer never
 * reaches public chat, and the answer arrives on the player's own thread. The prompt, the refusals and
 * the suggestions are text — never markup — unless the plugin passes markup on purpose with
 * {@link #promptMarkup}.
 */
public final class Question<T> {

    /** How long a question waits for an answer, unless told otherwise. */
    public static final Duration DEFAULT_WAIT = Duration.ofMinutes(2);
    /** How many wrong answers before it stops asking, unless told otherwise. */
    public static final int DEFAULT_ATTEMPTS = 3;

    private final Parser<T> parser;
    private String promptMarkup = "";
    private final List<String> suggestions = new ArrayList<>();
    private int attempts = DEFAULT_ATTEMPTS;
    private Duration wait = DEFAULT_WAIT;
    private String owner = "question";
    private Consumer<T> onAnswer = value -> { };
    private Runnable onCancel = () -> { };

    private Question(Parser<T> parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    /** A question whose answer is read by this parser — see {@link Parsers}. */
    public static <T> Question<T> asking(Parser<T> parser) {
        return new Question<>(parser);
    }

    /** What is asked, as plain text. */
    public Question<T> prompt(String text) {
        this.promptMarkup = Text.literal(text);
        return this;
    }

    /** What is asked, as markup the plugin wrote — never anything a player typed. */
    public Question<T> promptMarkup(String miniMessage) {
        this.promptMarkup = miniMessage == null ? "" : miniMessage;
        return this;
    }

    /** Answers offered as clickable text — clicking puts it in the chat box, ready to send or edit. */
    public Question<T> suggest(String... answers) {
        for (String answer : answers) {
            if (answer != null && !answer.isBlank()) {
                suggestions.add(answer);
            }
        }
        return this;
    }

    /** How many tries before it gives up; at least one. */
    public Question<T> attempts(int tries) {
        this.attempts = Math.max(1, tries);
        return this;
    }

    /** How long it waits for each answer. */
    public Question<T> within(Duration wait) {
        this.wait = wait == null || wait.isNegative() || wait.isZero() ? DEFAULT_WAIT : wait;
        return this;
    }

    /** Who is asking — a plugin's name, for diagnosing a stuck prompt. */
    public Question<T> owner(String owner) {
        this.owner = owner == null || owner.isBlank() ? "question" : owner;
        return this;
    }

    /** What happens with a good answer, on the player's own thread. */
    public Question<T> onAnswer(Consumer<T> onAnswer) {
        this.onAnswer = onAnswer == null ? value -> { } : onAnswer;
        return this;
    }

    /** What happens when they say cancel, run out of tries or time, or leave. */
    public Question<T> onCancel(Runnable onCancel) {
        this.onCancel = onCancel == null ? () -> { } : onCancel;
        return this;
    }

    /**
     * Asks a player, through Core's own prompts, chat buttons and wording.
     *
     * @return whether it was asked — false when another plugin is already asking them something, in
     *         which case they are told to answer that first
     */
    public boolean ask(Player player) {
        RainsCore core = RainsCore.get();
        return ask(player.getUniqueId(), core.prompts(), player::sendMessage, core.buttons(), core.messages(),
                System::currentTimeMillis);
    }

    /**
     * The same, with every collaborator given — what a test, or a plugin with its own wording, uses.
     *
     * @param say      how a line reaches the player
     * @param buttons  how suggestions become clickable; null shows them as text
     * @param messages where the wording comes from; null uses the built-in English
     */
    public boolean ask(UUID player, ChatPrompts prompts, Consumer<Component> say, ChatButtons buttons,
                       Messages messages, LongSupplier clock) {
        Objects.requireNonNull(prompts, "prompts");
        Objects.requireNonNull(say, "say");
        return new Attempt(player, prompts, say, buttons, messages, clock).ask(attempts);
    }

    /** One run of asking, with its own count of tries left. */
    private final class Attempt {
        private final UUID player;
        private final ChatPrompts prompts;
        private final Consumer<Component> say;
        private final ChatButtons buttons;
        private final Messages messages;
        private final LongSupplier clock;
        private long deadline;

        Attempt(UUID player, ChatPrompts prompts, Consumer<Component> say, ChatButtons buttons,
                Messages messages, LongSupplier clock) {
            this.player = player;
            this.prompts = prompts;
            this.say = say;
            this.buttons = buttons;
            this.messages = messages;
            this.clock = clock == null ? System::currentTimeMillis : clock;
        }

        boolean ask(int triesLeft) {
            deadline = clock.getAsLong() + wait.toMillis();
            boolean asked = prompts.ask(player, owner, wait, typed -> answered(typed, triesLeft), this::stopped);
            if (!asked) {
                say.accept(words("prompt.busy",
                        "<yellow>Something else is already waiting for your answer. Answer or cancel that first."));
                return false;
            }
            if (!promptMarkup.isEmpty()) {
                say.accept(Text.render("<gold>" + promptMarkup));
            }
            say.accept(words("prompt.how",
                    "<dark_gray>Type your answer in chat, or <white>cancel</white>. <seconds>s to answer.",
                    "seconds", wait.toSeconds()));
            if (!suggestions.isEmpty()) {
                say.accept(suggestionRow());
            }
            return true;
        }

        private void answered(String typed, int triesLeft) {
            Parsed<T> parsed;
            try {
                parsed = parser.parse(typed);
            } catch (RuntimeException broken) {
                parsed = Parsed.no("That could not be read.");
            }
            if (parsed.isOk()) {
                onAnswer.accept(parsed.value());
                return;
            }
            int left = triesLeft - 1;
            if (left <= 0) {
                say.accept(words("prompt.gave-up", "<red><problem> That was the last try; nothing was changed.",
                        "problem", parsed.problem()));
                onCancel.run();
                return;
            }
            say.accept(words("prompt.again", "<red><problem> <gray>Try again, <left> <tries> left.",
                    "problem", parsed.problem(), "left", left, "tries", left == 1 ? "try" : "tries"));
            ask(left);
        }

        private void stopped() {
            boolean timedOut = clock.getAsLong() >= deadline;
            say.accept(timedOut
                    ? words("prompt.timed-out", "<gray>No answer came, so nothing was changed. The silence was deafening.")
                    : words("prompt.cancelled", "<gray>Cancelled. Nothing was changed."));
            onCancel.run();
        }

        private Component suggestionRow() {
            Component row = words("prompt.suggestions", "<dark_gray>Click one: ");
            for (String answer : suggestions) {
                Component shown = buttons == null
                        ? Text.render("<aqua>[<answer>]", "answer", answer)
                        : buttons.label("<aqua>[" + Text.literal(answer) + "]")
                                .tooltip("<gray>Put " + Text.literal(answer) + " in the chat box").suggests(answer).render();
                row = row.append(shown).append(Component.space());
            }
            return row;
        }

        private Component words(String key, String builtIn, Object... values) {
            if (messages != null && messages.has(key)) {
                return messages.get(key, values);
            }
            return Text.render(builtIn, values);
        }
    }

}
