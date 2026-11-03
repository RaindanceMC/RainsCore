package de.raindancer.core.content.items;

import de.raindancer.core.ui.text.NameSearch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code /give <player> search <words>}: which items answer, ranked the way the shop's search ranks. */
public final class GiveSearch {

    private GiveSearch() {
    }

    private record Found(String key, String name, int score) {
    }

    /**
     * The keys whose name answers {@code typed}, closest first, then by name.
     *
     * @param names every item's key and the name a player reads
     */
    public static List<String> rank(String typed, Map<String, String> names) {
        if (typed == null || typed.isBlank()) {
            return List.of();
        }
        List<Found> found = new ArrayList<>();
        names.forEach((key, name) -> {
            Optional<Integer> score = NameSearch.score(typed, name);
            score.ifPresent(value -> found.add(new Found(key, name, value)));
        });
        found.sort(Comparator.comparingInt(Found::score).thenComparing(Found::name));
        return found.stream().map(Found::key).toList();
    }
}
