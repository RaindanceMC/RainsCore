package de.raindancer.core.ui.help;

import java.util.List;

/**
 * One thing the guide can explain — "the economy", "the shop", "claims" — as written in a {@code rains-help/<id>.yml}.
 *
 * <p>A topic is a short conversation: the guide's opening words ({@link #intro}), then the questions a player can
 * ask ({@link #sections}), each with its answer. Which questions appear, and what the answers say, is decided when
 * somebody asks — by the server's settings, the reader's permissions and what is installed.
 *
 * @param plugin    who shipped it — filled in when it is loaded, never written in the file
 * @param generated made from a plugin's command list because it shipped no guide of its own
 */
public record HelpTopic(String id, String title, String summary, String intro, String icon, String category,
                        int order, List<String> aliases, List<String> keywords, String module, String permission,
                        HelpExpression when, String wiki, List<String> commands, List<String> related,
                        List<HelpSection> sections, String plugin, boolean generated) {

    public HelpTopic {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        commands = commands == null ? List.of() : List.copyOf(commands);
        related = related == null ? List.of() : List.copyOf(related);
        sections = sections == null ? List.of() : List.copyOf(sections);
        category = category == null || category.isBlank() ? HelpCategories.OTHER : category.strip();
        summary = summary == null ? "" : summary.strip();
        intro = intro == null ? "" : intro.strip();
        plugin = plugin == null ? "" : plugin;
    }

    /** The same topic, as shipped by {@code plugin}. */
    public HelpTopic from(String owner) {
        return new HelpTopic(id, title, summary, intro, icon, category, order, aliases, keywords, module, permission,
                when, wiki, commands, related, sections, owner, generated);
    }

    /** What the guide says first: the intro when there is one, the summary otherwise. */
    public String opening() {
        return intro.isEmpty() ? summary : intro;
    }
}
