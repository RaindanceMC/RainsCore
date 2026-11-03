package de.raindancer.core.ui.tree;

import org.bukkit.plugin.Plugin;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Every skill any plugin has, for a menu that lists them all. Safe from any thread. */
public final class Skills {

    private record Entry(Plugin owner, SkillView view) {
    }

    private static final List<Entry> entries = new CopyOnWriteArrayList<>();

    private Skills() {
    }

    /** Adds a skill; one already there under the same id is replaced. */
    public static synchronized void register(Plugin owner, SkillView view) {
        entries.removeIf(entry -> entry.view().id().equals(view.id()));
        entries.add(new Entry(owner, view));
    }

    public static synchronized boolean unregister(String id) {
        return entries.removeIf(entry -> entry.view().id().equals(id));
    }

    public static List<SkillView> all() {
        return entries.stream().map(Entry::view)
                .sorted(Comparator.comparingInt(SkillView::order).thenComparing(SkillView::title)).toList();
    }

    public static Optional<SkillView> byId(String id) {
        return entries.stream().map(Entry::view).filter(view -> view.id().equals(id)).findFirst();
    }

    public static int forgetFrom(ClassLoader loader) {
        int before = entries.size();
        entries.removeIf(entry -> entry.owner() != null && entry.owner().getClass().getClassLoader() == loader);
        return before - entries.size();
    }

    public static void clear() {
        entries.clear();
    }
}
