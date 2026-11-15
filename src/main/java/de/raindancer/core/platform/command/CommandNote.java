package de.raindancer.core.platform.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One command, as it appears in the directory: a name, a sentence, and its options.
 *
 * <h2>Why a sentence and not a paragraph</h2>
 * Because the directory is a directory. Somebody opening it is trying to remember whether the
 * command is {@code /warp} or {@code /warps}, not to learn what warping is — and a page of prose per
 * command turns thirty commands into forty pages nobody scrolls through. The explaining is the
 * manual's job; this says what exists, and every option it takes.
 *
 * <p>The sentence is therefore held to one: {@link #of} refuses a second full stop's worth of text,
 * loudly, at the moment the command is declared rather than in a book somebody is reading.
 *
 * <h2>Why the options are strings</h2>
 * They are usage lines — {@code "pack <creature> [how many]"} — and a usage line is what a reader
 * needs to see. Modelling arguments properly would buy nothing here: nothing validates against these,
 * they are printed, and Brigadier already owns the real grammar.
 */
public record CommandNote(String plugin, String command, String sentence, List<String> options,
                          String permission) implements Comparable<CommandNote> {

    /** The longest a sentence may be before it stops being one. Generous, and still a sentence. */
    public static final int LONGEST_SENTENCE = 120;

    public CommandNote {
        plugin = required(plugin, "plugin");
        command = normalise(command);
        sentence = checkedSentence(sentence, command);
        options = options == null ? List.of() : List.copyOf(options);
        permission = permission == null || permission.isBlank() ? null : permission.trim();
    }

    /** A command with no options — the common case. */
    public static CommandNote of(String plugin, String command, String sentence) {
        return new CommandNote(plugin, command, sentence, List.of(), null);
    }

    /** The same, plus its usage lines. */
    public static CommandNote of(String plugin, String command, String sentence, String... options) {
        return new CommandNote(plugin, command, sentence, List.of(options), null);
    }

    /** Who may see it in the book. Null means everybody. */
    public CommandNote needing(String node) {
        return new CommandNote(plugin, command, sentence, options, node);
    }

    /** More usage lines. Accumulates, so it may be called more than once. */
    public CommandNote taking(String... more) {
        List<String> all = new ArrayList<>(options);
        if (more != null) {
            all.addAll(List.of(more));
        }
        return new CommandNote(plugin, command, sentence, all, permission);
    }

    /** With a leading slash, which is how it is written everywhere a reader sees it. */
    public String slashed() {
        return "/" + command;
    }

    /** Alphabetical within a plugin, which is the order a directory is read in. */
    @Override
    public int compareTo(CommandNote other) {
        int byPlugin = plugin.compareToIgnoreCase(other.plugin);
        return byPlugin != 0 ? byPlugin : command.compareToIgnoreCase(other.command);
    }

    private static String normalise(String command) {
        String name = required(command, "command name").trim().toLowerCase(Locale.ROOT);
        while (name.startsWith("/")) {
            // Written both ways by different callers, and a directory listing "//home" is a directory
            // nobody trusts. Taken either way and stored one way.
            name = name.substring(1);
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("a command needs a name, not just a slash");
        }
        return name;
    }

    private static String checkedSentence(String sentence, String command) {
        String said = required(sentence, "the sentence for /" + command).trim();
        if (said.length() > LONGEST_SENTENCE) {
            // Said here rather than tolerated, because the failure otherwise is a book that reads
            // fine on the page it was written for and overruns three pages later.
            throw new IllegalArgumentException("/" + command + " describes itself in " + said.length()
                    + " characters. The directory takes one sentence, at most " + LONGEST_SENTENCE
                    + ". Anything longer belongs in that plugin's own manual.");
        }
        return said;
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        return value.trim();
    }
}
