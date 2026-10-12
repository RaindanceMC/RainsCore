package de.raindancer.core.ui.hub;

import de.raindancer.core.platform.command.CommandDirectory;
import de.raindancer.core.platform.command.CommandNote;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Everything installed that wants a door in {@code /hub}, as reported by whoever owns it.
 *
 * <p>Core's for the same reason {@link CommandDirectory} is: only Core is visible to every plugin, so only
 * Core can hold the whole list. Reported, not discovered — a module says what it is as it enables.
 */
public final class Hub {

    private final Map<String, HubEntry> entries = new LinkedHashMap<>();

    public synchronized Hub declare(HubEntry entry) {
        if (entry != null) {
            entries.put(entry.id().toLowerCase(Locale.ROOT), entry);
        }
        return this;
    }

    public synchronized boolean forget(String id) {
        return id != null && entries.remove(id.toLowerCase(Locale.ROOT)) != null;
    }

    public synchronized List<HubEntry> all() {
        List<HubEntry> found = new ArrayList<>(entries.values());
        found.sort(Comparator.comparing(HubEntry::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(found);
    }

    /**
     * What one reader may open. A module is absent, not greyed, when it has commands and the reader may
     * use none of them; commands nobody reported cannot be judged, so they count as usable.
     */
    public List<HubEntry> visibleTo(Predicate<String> mayUse, CommandDirectory directory) {
        Set<String> reported = new HashSet<>();
        Set<String> usable = new HashSet<>();
        for (CommandNote note : directory.all()) {
            reported.add(root(note.command()));
        }
        for (CommandNote note : directory.visibleTo(mayUse)) {
            usable.add(root(note.command()));
        }
        return all().stream()
                .filter(entry -> entry.permission() == null || mayUse.test(entry.permission()))
                .filter(entry -> entry.commands().isEmpty() || entry.commands().stream()
                        .map(Hub::root)
                        .anyMatch(name -> !reported.contains(name) || usable.contains(name)))
                .toList();
    }

    /** Exact id or name first, then the first whose id or name contains what was typed. */
    public static Optional<HubEntry> find(List<HubEntry> entries, String typed) {
        if (typed == null || typed.isBlank()) {
            return Optional.empty();
        }
        String wanted = typed.trim().toLowerCase(Locale.ROOT);
        return entries.stream()
                .filter(e -> e.id().equalsIgnoreCase(wanted) || e.name().equalsIgnoreCase(wanted))
                .findFirst()
                .or(() -> entries.stream()
                        .filter(e -> e.id().toLowerCase(Locale.ROOT).contains(wanted)
                                || e.name().toLowerCase(Locale.ROOT).contains(wanted))
                        .findFirst());
    }

    /** The directory entries of one door's commands (sub-commands included) that the reader may see. */
    public static List<CommandNote> notesFor(HubEntry entry, CommandDirectory directory,
                                             Predicate<String> mayUse) {
        Set<String> roots = new HashSet<>();
        entry.commands().forEach(name -> roots.add(root(name)));
        return directory.visibleTo(mayUse).stream()
                .filter(note -> roots.contains(root(note.command())))
                .toList();
    }

    private static String root(String command) {
        String name = command.trim().toLowerCase(Locale.ROOT);
        int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(0, space);
    }
}
