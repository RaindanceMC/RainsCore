package de.raindancer.core.ui.help;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a {@code rains-help/<id>.yml} into a {@link HelpTopic}, and says everything wrong with it.
 *
 * <p>Strict about what would mislead a player — no id, a question without an answer, a condition that cannot be
 * read — and loud about typos: an unknown field is a problem, because {@code sectoins:} would otherwise be a topic
 * with nothing in it that nobody notices.
 */
public final class HelpTopicParser {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");

    private static final Set<String> TOPIC_FIELDS = Set.of("id", "title", "summary", "intro", "icon", "category",
            "order", "aliases", "keywords", "module", "permission", "when", "wiki", "commands", "related", "sections");
    private static final Set<String> SECTION_FIELDS = Set.of("question", "title", "text", "when", "permission");

    /** The most questions one topic offers. A dialog shows them all at once, and twenty buttons is a wall. */
    public static final int MOST_SECTIONS = 16;
    /** A button holds this many characters before the client cuts it. Generous for two columns. */
    public static final int LONGEST_QUESTION = 40;

    /** What reading one file found. */
    public record Parsed(Optional<HelpTopic> topic, List<String> problems) {
    }

    private HelpTopicParser() {
    }

    /**
     * @param source where it came from, for the problems — "RainsEconomy: rains-help/economy.yml"
     */
    public static Parsed parse(String yaml, String source) {
        List<String> problems = new ArrayList<>();
        YamlConfiguration file = new YamlConfiguration();
        try {
            file.loadFromString(yaml == null ? "" : yaml);
        } catch (InvalidConfigurationException broken) {
            problems.add(source + ": not valid YAML — " + firstLine(broken.getMessage()));
            return new Parsed(Optional.empty(), problems);
        }
        return fromSection(file, source, problems);
    }

    /** The same, from a section already read — a topic written into an override document. */
    public static Parsed fromSection(ConfigurationSection file, String source, List<String> problems) {
        for (String key : file.getKeys(false)) {
            if (!TOPIC_FIELDS.contains(key)) {
                problems.add(source + ": '" + key + "' is not a field of a topic (" + String.join(", ",
                        TOPIC_FIELDS.stream().sorted().toList()) + ")");
            }
        }
        String id = text(file.get("id")).toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            problems.add(source + ": needs an id — lower case letters, digits and dashes — not '" + id + "'");
            return new Parsed(Optional.empty(), problems);
        }
        String where = source + " (" + id + ")";
        String title = text(file.get("title"));
        if (title.isEmpty()) {
            problems.add(where + ": needs a title");
            return new Parsed(Optional.empty(), problems);
        }
        String summary = text(file.get("summary"));
        if (summary.isEmpty()) {
            problems.add(where + ": needs a summary — the one line the guide's list shows");
        } else if (summary.length() > 140) {
            problems.add(where + ": the summary is " + summary.length() + " characters; one line is 140 at most");
        }
        String icon = text(file.get("icon"));
        if (!icon.isEmpty() && Material.matchMaterial(icon) == null) {
            problems.add(where + ": '" + icon + "' is not an item");
            icon = "";
        }
        HelpExpression when = expression(file.get("when"), where, problems);
        String wiki = text(file.get("wiki"));
        if (!wiki.isEmpty() && !wiki.startsWith("https://")) {
            problems.add(where + ": the wiki link must start with https://");
            wiki = "";
        }

        List<HelpSection> sections = new ArrayList<>();
        Object written = file.get("sections");
        List<?> list = written instanceof List<?> items ? items : List.of();
        if (written != null && !(written instanceof List<?>)) {
            problems.add(where + ": sections must be a list (each starting with '- question:')");
        }
        int number = 0;
        for (Object each : list) {
            number++;
            Map<?, ?> section = asMap(each);
            if (section == null) {
                problems.add(where + ": section " + number + " is not a question with an answer");
                continue;
            }
            for (Object key : section.keySet()) {
                if (!SECTION_FIELDS.contains(String.valueOf(key))) {
                    problems.add(where + ": section " + number + " has '" + key + "', which is not a field of a "
                            + "section (" + String.join(", ", SECTION_FIELDS.stream().sorted().toList()) + ")");
                }
            }
            String question = text(section.get("question"));
            String answer = text(section.get("text"));
            if (question.isEmpty() || answer.isEmpty()) {
                problems.add(where + ": section " + number + " needs both a question and a text");
                continue;
            }
            if (question.length() > LONGEST_QUESTION) {
                problems.add(where + ": the question '" + question + "' is " + question.length()
                        + " characters; a button shows " + LONGEST_QUESTION);
            }
            sections.add(new HelpSection(question, emptyToNull(text(section.get("title"))), answer,
                    expression(section.get("when"), where + " section " + number, problems),
                    emptyToNull(text(section.get("permission")))));
        }
        if (sections.isEmpty() && list.isEmpty()) {
            problems.add(where + ": has no sections — what would the guide answer?");
        }
        if (sections.size() > MOST_SECTIONS) {
            problems.add(where + ": " + sections.size() + " questions is more than one dialog holds well ("
                    + MOST_SECTIONS + "); split it into two topics");
        }

        HelpTopic topic = new HelpTopic(id, title, summary, text(file.get("intro")), emptyToNull(icon),
                HelpCategories.canonical(text(file.get("category"))), file.getInt("order", 100),
                lowerList(file.getStringList("aliases")), lowerList(file.getStringList("keywords")),
                emptyToNull(text(file.get("module")).toLowerCase(Locale.ROOT)),
                emptyToNull(text(file.get("permission"))), when, emptyToNull(wiki),
                commandList(file.getStringList("commands")), lowerList(file.getStringList("related")), sections,
                null, false);
        return new Parsed(Optional.of(topic), problems);
    }

    private static HelpExpression expression(Object written, String where, List<String> problems) {
        String text = text(written);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return HelpExpression.parse(text);
        } catch (IllegalArgumentException broken) {
            problems.add(where + ": when — " + broken.getMessage());
            // Unreadable means hidden: a section about a feature that may be off must not show by accident.
            return HelpExpression.parse("@perm:rainscore.help.never.shown");
        }
    }

    private static List<String> lowerList(List<String> values) {
        return values.stream().map(value -> value.strip().toLowerCase(Locale.ROOT)).filter(value -> !value.isEmpty())
                .distinct().toList();
    }

    private static List<String> commandList(List<String> values) {
        return values.stream().map(value -> value.strip().toLowerCase(Locale.ROOT))
                .map(value -> value.startsWith("/") ? value.substring(1) : value)
                .filter(value -> !value.isEmpty()).distinct().toList();
    }

    private static Map<?, ?> asMap(Object each) {
        if (each instanceof Map<?, ?> map) {
            return map;
        }
        if (each instanceof ConfigurationSection section) {
            return section.getValues(false);
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unreadable";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
