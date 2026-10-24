package de.raindancer.core.ui.help;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One way to use a command, as a form a player can fill in: {@code /pay <player> <amount> [what for]} is three
 * fields; {@code /hire list} is none, and runs on a click.
 *
 * @param command the command with its slash and any subcommand words of its note — {@code /home set}
 * @param parts   what follows, in order: fixed words and fields
 * @param note    what the usage line said after its dash ("console only"), or empty
 */
public record CommandForm(String command, List<Part> parts, String note) {

    public sealed interface Part permits Word, Field {
    }

    /** A word typed as it is. */
    public record Word(String text) implements Part {
    }

    /**
     * Something the player fills in.
     *
     * @param key     the dialog input's key — {@code f0}, {@code f1}, …
     * @param choices fixed answers to pick from ({@code fire|quit}); empty for free text
     * @param players whether it asks for a player, so the form can offer who is online
     */
    public record Field(String key, String label, boolean required, List<String> choices, boolean players)
            implements Part {
    }

    public CommandForm {
        parts = List.copyOf(parts);
        note = note == null ? "" : note;
    }

    public List<Field> fields() {
        return parts.stream().filter(Field.class::isInstance).map(Field.class::cast).toList();
    }

    /** The usage as it reads: {@code /pay <player> <amount> [what for]}. */
    public String usage() {
        StringBuilder usage = new StringBuilder(command);
        for (Part part : parts) {
            usage.append(' ').append(switch (part) {
                case Word word -> word.text();
                case Field field -> {
                    String inner = field.choices().isEmpty() || !field.label().isEmpty()
                            ? field.label() : String.join("|", field.choices());
                    if (field.choices().size() > 1 && field.required() && !field.label().contains("|")
                            && field.label().equals(String.join("|", field.choices()))) {
                        yield inner;
                    }
                    yield field.required() ? (field.choices().isEmpty() ? "<" + inner + ">" : inner) : "[" + inner + "]";
                }
            });
        }
        return usage.toString();
    }

    /**
     * The command as it would be typed, from what was filled in — empty while a required field is blank. Values are
     * taken as typed (trimmed); a value with spaces stays one value at the end of the line, where commands read it.
     */
    public Optional<String> build(Map<String, String> values) {
        StringBuilder typed = new StringBuilder(command);
        for (Part part : parts) {
            switch (part) {
                case Word word -> typed.append(' ').append(word.text());
                case Field field -> {
                    String value = values.getOrDefault(field.key(), "").strip();
                    if (value.isEmpty()) {
                        if (field.required()) {
                            return Optional.empty();
                        }
                        continue;
                    }
                    typed.append(' ').append(value);
                }
            }
        }
        return Optional.of(typed.toString());
    }

    /** What to put into the chat bar for somebody who would rather type: the words up to the first field. */
    public String typeable() {
        StringBuilder typed = new StringBuilder(command);
        for (Part part : parts) {
            if (!(part instanceof Word word)) {
                break;
            }
            typed.append(' ').append(word.text());
        }
        return typed.append(' ').toString();
    }
}
