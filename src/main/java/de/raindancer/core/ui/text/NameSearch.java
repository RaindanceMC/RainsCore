package de.raindancer.core.ui.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Whether a name is what somebody typed, and how close — the shop's search, for every list searched by name. Every
 * word typed has to start a word of the name, in any order and any case, and a number stands for its Roman numeral — {@code sharp 5} is Sharpness V. A typo
 * still finds it: a word of four letters or more may be a letter off (missing, extra, wrong or two swapped), one of
 * seven or more two; such a match ranks after every correctly spelt one.
 */
public final class NameSearch {

    private NameSearch() {
    }

    private static final String[] ROMAN = {"", "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x"};
    /** Words that say nothing about the thing: "block of diamond" is the shop's Diamond Block. */
    private static final java.util.Set<String> FILLER = java.util.Set.of("of", "the", "a", "an");

    /**
     * Lower is closer: 0 the whole name, 1 a name that starts with it, 2 anywhere else, 3 only with a typo. Empty for
     * no match.
     */
    public static Optional<Integer> score(String typed, String name) {
        List<String> wanted = new ArrayList<>(words(typed));
        if (wanted.size() > 1) {
            wanted.removeIf(FILLER::contains);
        }
        if (wanted.isEmpty() || name == null) {
            return Optional.empty();
        }
        List<String> words = words(name);
        boolean misspelt = false;
        for (String want : wanted) {
            if (words.stream().anyMatch(word -> word.startsWith(want) || word.equals(roman(want)))) {
                continue;
            }
            if (words.stream().anyMatch(word -> nearly(want, word))) {
                misspelt = true;
                continue;
            }
            return Optional.empty();
        }
        if (misspelt) {
            return Optional.of(3);
        }
        if (String.join(" ", words).equals(String.join(" ", wanted))) {
            return Optional.of(0);
        }
        return Optional.of(words.getFirst().startsWith(wanted.getFirst()) ? 1 : 2);
    }

    /** A typo of the word, or of its start as long as what was typed — "diamnd" of diamond, "enchan" of enchanted. */
    public static boolean nearly(String typed, String word) {
        int allowed = typed.length() >= 7 ? 2 : typed.length() >= 4 ? 1 : 0;
        if (allowed == 0) {
            return false;
        }
        if (distance(typed, word) <= allowed) {
            return true;
        }
        for (int length = Math.max(1, typed.length() - allowed); length <= Math.min(word.length(), typed.length() + allowed);
             length++) {
            if (distance(typed, word.substring(0, length)) <= allowed) {
                return true;
            }
        }
        return false;
    }

    /** Edits between two words — a letter added, dropped, changed, or two neighbours swapped. */
    public static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String word : text.toLowerCase(Locale.ROOT).replace("minecraft:", "").split("[\\s_\\-()]+")) {
            if (!word.isBlank()) {
                out.add(word);
            }
        }
        return out;
    }

    private static String roman(String word) {
        try {
            int number = Integer.parseInt(word);
            return number > 0 && number < ROMAN.length ? ROMAN[number] : word;
        } catch (NumberFormatException notANumber) {
            return word;
        }
    }

}
