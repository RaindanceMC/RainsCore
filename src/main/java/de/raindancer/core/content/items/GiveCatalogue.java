package de.raindancer.core.content.items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** What {@code /give} suggests for what is typed, without a server: the plugin items whose name or key matches. */
public final class GiveCatalogue {

    private GiveCatalogue() {
    }

    /**
     * The keys of {@code namespace} (all for null) whose name or key holds every word searched, sorted by key.
     *
     * @param names a key's display name, markup and all
     */
    public static List<String> filter(List<String> keys, String namespace, String search,
                                      Function<String, String> names) {
        String[] words = search == null || search.isBlank() ? new String[0]
                : search.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<String> kept = new ArrayList<>();
        for (String key : keys) {
            if (namespace != null && !key.startsWith(namespace + ":")) {
                continue;
            }
            String haystack = plain(names.apply(key)) + " " + key.replace('_', ' ').replace('-', ' ');
            boolean all = true;
            for (String word : words) {
                if (!haystack.contains(word)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                kept.add(key);
            }
        }
        kept.sort(null);
        return kept;
    }

    /** A name as words: MiniMessage tags and legacy colour codes out, lower case. */
    static String plain(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("<[^>]*>", "").replaceAll("§.", "").toLowerCase(Locale.ROOT).trim();
    }

    /** {@code fishing: rod-void 3} is {@code fishing:rod-void 3}: a space only after a first word ending in a colon. */
    public static String joined(String typed) {
        return typed.replaceFirst("^([a-z0-9_.-]+:)\\s+", "$1");
    }

    /**
     * The item somebody most likely meant by a key nobody makes: a plugin's key (in full) or a vanilla item (bare,
     * {@code diamond}). Close by spelling — the whole key, the part after the colon, or the item's name — and never
     * a wild guess: past a third of the word wrong, nothing.
     *
     * @param vanilla vanilla's items in full ({@code minecraft:diamond})
     * @param names   a plugin key's display name
     */
    public static java.util.Optional<String> didYouMean(String typed, List<String> ours, List<String> vanilla,
                                                        Function<String, String> names) {
        String wanted = typed == null ? "" : typed.toLowerCase(Locale.ROOT).trim();
        if (wanted.isEmpty()) {
            return java.util.Optional.empty();
        }
        String wantedId = idOf(wanted);
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        List<String[]> candidates = new ArrayList<>();
        for (String key : ours) {
            candidates.add(new String[]{key, key, compact(plain(names.apply(key)))});
        }
        for (String key : vanilla) {
            candidates.add(new String[]{idOf(key), key, idOf(key).replace("_", "")});
        }
        for (String[] candidate : candidates) {
            String shown = candidate[0];
            String key = candidate[1];
            int score = Math.min(distance(wanted, key), Math.min(distance(wantedId, idOf(key)),
                    distance(compact(wantedId), candidate[2])));
            if (score < bestScore) {
                bestScore = score;
                best = shown;
            }
        }
        int allowed = Math.max(1, Math.min(3, wantedId.length() / 3));
        return bestScore <= allowed ? java.util.Optional.ofNullable(best) : java.util.Optional.empty();
    }

    private static String idOf(String key) {
        return key.substring(key.indexOf(':') + 1);
    }

    private static String compact(String text) {
        return text.replaceAll("[^a-z0-9]", "");
    }

    /** Levenshtein: how many letters to add, drop or change to get from one to the other. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int change = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + change);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
