package de.raindancer.core.ui.scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How one player wants their sidebar: which sections they hid, and in what order they put the rest. Everything
 * else — the title with the server's name, what a section says — is the server's. Knows nothing about Minecraft,
 * so {@link SidebarPreferences} can keep it as one line and the menu can be tested without a server.
 *
 * @param order  section owners in the order the player put them; owners not named come after, by rank
 * @param hidden section owners the player switched off
 * @param sizes  how big the player wants a section that has a size — how many of the richest
 */
public record SidebarLayout(List<String> order, Set<String> hidden, java.util.Map<String, Integer> sizes) {

    public static final SidebarLayout DEFAULT = new SidebarLayout(List.of(), Set.of(), java.util.Map.of());

    public SidebarLayout {
        order = order == null ? List.of() : List.copyOf(new LinkedHashSet<>(order));
        hidden = hidden == null ? Set.of() : Set.copyOf(hidden);
        sizes = sizes == null ? java.util.Map.of() : java.util.Map.copyOf(sizes);
    }

    /** Before sizes. */
    public SidebarLayout(List<String> order, Set<String> hidden) {
        this(order, hidden, java.util.Map.of());
    }

    /** The size the player chose for a section, or {@code fallback} when they chose none. */
    public int sizeOf(String owner, int fallback) {
        return sizes.getOrDefault(owner, fallback);
    }

    public SidebarLayout sized(String owner, int size) {
        java.util.Map<String, Integer> now = new java.util.LinkedHashMap<>(sizes);
        now.put(owner, size);
        return new SidebarLayout(order, hidden, now);
    }

    /** One section as the layout sees it: who it belongs to and where it sits by default. */
    public record Known(String owner, int rank) {
    }

    public boolean shows(String owner) {
        return !hidden.contains(owner);
    }

    public SidebarLayout toggled(String owner) {
        Set<String> now = new LinkedHashSet<>(hidden);
        if (!now.remove(owner)) {
            now.add(owner);
        }
        return new SidebarLayout(order, now, sizes);
    }

    /** The sections in the order this player sees them: theirs first as they put them, the rest by rank. */
    public List<String> arrange(List<Known> known) {
        List<Known> rest = new ArrayList<>(known.stream().filter(each -> !order.contains(each.owner())).toList());
        rest.sort(Comparator.comparingInt(Known::rank).thenComparing(Known::owner));
        List<String> arranged = new ArrayList<>();
        for (String owner : order) {
            if (known.stream().anyMatch(each -> each.owner().equals(owner))) {
                arranged.add(owner);
            }
        }
        rest.forEach(each -> arranged.add(each.owner()));
        return arranged;
    }

    /** The same layout with {@code owner} one place earlier among {@code known}; the first stays first. */
    public SidebarLayout movedUp(String owner, List<Known> known) {
        return moved(owner, known, -1);
    }

    public SidebarLayout movedDown(String owner, List<Known> known) {
        return moved(owner, known, 1);
    }

    private SidebarLayout moved(String owner, List<Known> known, int by) {
        List<String> arranged = new ArrayList<>(arrange(known));
        int at = arranged.indexOf(owner);
        int to = at + by;
        if (at < 0 || to < 0 || to >= arranged.size()) {
            return this;
        }
        arranged.remove(at);
        arranged.add(to, owner);
        return new SidebarLayout(arranged, hidden, sizes);
    }

    /** Back to the server's order with everything shown. */
    public SidebarLayout reset() {
        return DEFAULT;
    }

    public boolean isDefault() {
        return order.isEmpty() && hidden.isEmpty() && sizes.isEmpty();
    }

    // ------------------------------------------------------------------ one line, for the player's data

    /** {@code order:a,b;hidden:c;sizes:d=5} — owners are {@code [a-z0-9._-]}, so the separators are safe. */
    public String asLine() {
        String line = "order:" + String.join(",", order) + ";hidden:" + String.join(",", hidden);
        if (sizes.isEmpty()) {
            return line;
        }
        List<String> sized = new ArrayList<>();
        sizes.forEach((owner, size) -> sized.add(owner + "=" + size));
        java.util.Collections.sort(sized);
        return line + ";sizes:" + String.join(",", sized);
    }

    public static SidebarLayout fromLine(String line) {
        if (line == null || line.isBlank()) {
            return DEFAULT;
        }
        List<String> order = new ArrayList<>();
        Set<String> hidden = new LinkedHashSet<>();
        java.util.Map<String, Integer> sizes = new java.util.LinkedHashMap<>();
        for (String part : line.split(";")) {
            int colon = part.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = part.substring(0, colon).strip();
            List<String> values = new ArrayList<>();
            for (String value : part.substring(colon + 1).split(",")) {
                if (!value.isBlank()) {
                    values.add(value.strip());
                }
            }
            if (key.equals("order")) {
                order.addAll(values);
            } else if (key.equals("hidden")) {
                hidden.addAll(values);
            } else if (key.equals("sizes")) {
                for (String value : values) {
                    int equals = value.indexOf('=');
                    try {
                        sizes.put(value.substring(0, equals), Integer.parseInt(value.substring(equals + 1)));
                    } catch (RuntimeException unreadable) {
                        // A size somebody edited by hand into nonsense is no size.
                    }
                }
            }
        }
        return new SidebarLayout(order, hidden, sizes);
    }
}
