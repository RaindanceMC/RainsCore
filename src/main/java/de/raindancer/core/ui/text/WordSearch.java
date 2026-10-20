package de.raindancer.core.ui.text;

import java.util.Locale;

/**
 * Searching by typed words, the one way every search box on the server works: every word typed must appear
 * somewhere in what is searched, in any order and case, underscores read as spaces — {@code "sword dia"} finds a
 * Diamond Sword, {@code "oak_log"} an Oak Log. Nothing typed finds everything.
 */
public final class WordSearch {

    private WordSearch() {
    }

    /** Whether every word of {@code typed} is in one of {@code texts}, taken together. */
    public static boolean matches(String typed, String... texts) {
        String wanted = normal(typed);
        if (wanted.isEmpty()) {
            return true;
        }
        StringBuilder all = new StringBuilder();
        if (texts != null) {
            for (String text : texts) {
                if (text != null) {
                    all.append(' ').append(normal(text));
                }
            }
        }
        String haystack = all.toString();
        for (String word : wanted.split(" ")) {
            if (!haystack.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /** Lower case, underscores as spaces, runs of spaces as one. */
    public static String normal(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ").strip();
    }
}
