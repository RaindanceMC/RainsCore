package de.raindancer.core.ui.help;

import de.raindancer.core.platform.command.CommandNote;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns the usage lines plugins declare ({@link CommandNote#options()}) into forms a player can fill in.
 *
 * <p>The lines are written for people, not parsers, so this reads them the way a person does:
 * <ul>
 *   <li>{@code <x>} must be filled in, {@code [x]} may be; {@code [what for]} is one field with a space in its name.</li>
 *   <li>{@code fire|quit} is a pick between fixed words; {@code [heads|tails]} the same, optional.</li>
 *   <li>{@code a | b} — spaced — is two separate ways to use the command.</li>
 *   <li>A line wholly in brackets ({@code [take <amount>]}) means the command works without it too.</li>
 *   <li>{@code (nothing)} means it takes nothing; {@code — console only} after a dash is a note, not a word.</li>
 * </ul>
 * A line that cannot be read sensibly still becomes a form — the worst case is a field too many, never a command
 * run with words its plugin did not ask for.
 */
public final class CommandForms {

    private CommandForms() {
    }

    /** Every form of a command, the bare command first when it can be run without anything. */
    public static List<CommandForm> of(CommandNote note) {
        String command = "/" + note.command();
        List<CommandForm> forms = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (note.options().isEmpty()) {
            add(forms, seen, new CommandForm(command, List.of(), ""));
        }
        for (String option : note.options()) {
            String text = option.strip();
            String remark = "";
            int dash = text.indexOf(" — ");
            if (dash >= 0) {
                remark = text.substring(dash + 3).strip();
                text = text.substring(0, dash).strip();
            }
            if (text.isEmpty() || text.equalsIgnoreCase("(nothing)")) {
                add(forms, seen, new CommandForm(command, List.of(), remark));
                continue;
            }
            if (text.startsWith("[") && closing(text, 0) == text.length() - 1) {
                // Wholly optional: the command works bare, or with any one of what is inside.
                add(forms, seen, new CommandForm(command, List.of(), remark));
                text = text.substring(1, text.length() - 1).strip();
            }
            for (String alternative : splitTopLevel(text, " | ")) {
                add(forms, seen, new CommandForm(command, parts(alternative.strip(), false, new int[]{0}), remark));
            }
        }
        return List.copyOf(forms);
    }

    /** A declared command and the one form of it a text means. */
    public record Match(CommandNote note, CommandForm form) {
    }

    /**
     * What a usage written in a help text — {@code /home set <name>} — means among the declared commands: the note
     * whose command is the longest start of it, and the form its remaining words describe. A usage with nothing after
     * the command gets the command's own first form, so {@code {cmd:/pay}} still asks for the player and amount.
     */
    public static java.util.Optional<Match> forUsage(String usage, List<CommandNote> notes) {
        String typed = usage == null ? "" : usage.strip();
        while (typed.startsWith("/")) {
            typed = typed.substring(1);
        }
        String lower = typed.toLowerCase(Locale.ROOT);
        CommandNote best = null;
        for (CommandNote note : notes) {
            String name = note.command();
            if ((lower.equals(name) || lower.startsWith(name + " "))
                    && (best == null || name.length() > best.command().length())) {
                best = note;
            }
        }
        if (best == null) {
            return java.util.Optional.empty();
        }
        String rest = typed.substring(best.command().length()).strip();
        CommandNote chosen = best;
        if (rest.isEmpty()) {
            List<CommandForm> own = of(best);
            return own.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(new Match(chosen, own.getFirst()));
        }
        List<CommandForm> written = of(new CommandNote(best.plugin(), best.command(), best.sentence(), List.of(rest),
                best.permission()));
        return written.stream().filter(form -> !form.parts().isEmpty()).findFirst()
                .or(() -> written.stream().findFirst())
                .map(form -> new Match(chosen, form));
    }

    private static void add(List<CommandForm> forms, Set<String> seen, CommandForm form) {
        if (seen.add(form.usage())) {
            forms.add(form);
        }
    }

    private static List<CommandForm.Part> parts(String text, boolean optional, int[] next) {
        List<CommandForm.Part> parts = new ArrayList<>();
        for (String token : splitTopLevel(text, " ")) {
            String word = token.strip();
            if (word.isEmpty()) {
                continue;
            }
            if (word.startsWith("<") && word.endsWith(">")) {
                parts.add(field(word.substring(1, word.length() - 1), !optional, next));
            } else if (word.startsWith("[") && word.endsWith("]")) {
                String inner = word.substring(1, word.length() - 1).strip();
                if (inner.contains("<") || inner.contains("[")) {
                    // A group with fields of its own ("[take <amount>]"): its words and fields, all optional.
                    parts.addAll(parts(inner, true, next));
                } else {
                    parts.add(field(inner, false, next));
                }
            } else if (word.contains("|")) {
                List<String> choices = Arrays.stream(word.split("\\|")).map(String::strip).filter(s -> !s.isEmpty())
                        .toList();
                parts.add(new CommandForm.Field("f" + next[0]++, word, !optional, choices, false));
            } else {
                parts.add(new CommandForm.Word(word));
            }
        }
        return parts;
    }

    private static CommandForm.Field field(String label, boolean required, int[] next) {
        String clean = label.strip();
        List<String> choices = clean.contains("|")
                ? Arrays.stream(clean.split("\\|")).map(String::strip).filter(s -> !s.isEmpty()).toList()
                : List.of();
        boolean players = choices.isEmpty() && clean.toLowerCase(Locale.ROOT).contains("player");
        return new CommandForm.Field("f" + next[0]++, clean, required, choices, players);
    }

    /** Splits on {@code separator} where no bracket is open. */
    static List<String> splitTopLevel(String text, String separator) {
        List<String> pieces = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c == '<' || c == '[') {
                depth++;
            } else if ((c == '>' || c == ']') && depth > 0) {
                depth--;
            } else if (depth == 0 && text.startsWith(separator, index)) {
                pieces.add(text.substring(start, index));
                start = index + separator.length();
                index = start - 1;
            }
        }
        pieces.add(text.substring(start));
        return pieces.stream().filter(piece -> !piece.isBlank()).toList();
    }

    /** Where the bracket opened at {@code from} closes, or -1. */
    private static int closing(String text, int from) {
        int depth = 0;
        for (int index = from; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c == '<' || c == '[') {
                depth++;
            } else if (c == '>' || c == ']') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }
}
