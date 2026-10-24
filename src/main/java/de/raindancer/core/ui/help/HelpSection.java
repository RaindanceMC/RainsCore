package de.raindancer.core.ui.help;

/**
 * One question in a topic, and the guide's answer.
 *
 * @param question what the button says — "How do I earn money?" — written as the player would ask it
 * @param title    a short name for lists and search results; the question when not given
 * @param text     the answer: MiniMessage with {@link HelpText} placeholders
 * @param when     shown only while this holds; null for always
 * @param permission shown only to readers holding it; null for everybody
 */
public record HelpSection(String question, String title, String text, HelpExpression when, String permission) {

    public HelpSection {
        title = title == null || title.isBlank() ? question : title.strip();
    }
}
