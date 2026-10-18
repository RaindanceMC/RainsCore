package de.raindancer.core.data.settings;

import java.util.ArrayList;
import java.util.List;

/**
 * One screen of the settings: what is on it, and where it sits.
 *
 * <p>A value, worked out by {@link SettingsNavigation} and handed to whatever draws it. Keeping it
 * separate from the drawing is what lets "which page shows what" be a test rather than a thing
 * somebody clicks through — see {@code SettingsNavigationTest}.
 *
 * @param path      where this page is, or null for the root
 * @param subtopics the categories on it, already filtered to the ones with something in them
 * @param settings  the settings on it, in the order their plugin declared them
 * @param trail     the names from the root inwards, for the window title
 * @param scope     the plugin a page belongs to ({@code "enchants"}), so its sections' paths can be made; null
 *                  for the front page and the old combined tree
 * @param parent    where Back goes, or null for the front page
 */
public record SettingsPage(String path, String title, List<SettingsTopic> subtopics,
                           List<Setting<?>> settings, List<String> trail, String scope, String parent) {

    public SettingsPage {
        subtopics = subtopics == null ? List.of() : List.copyOf(subtopics);
        settings = settings == null ? List.of() : List.copyOf(settings);
        trail = trail == null ? List.of() : List.copyOf(trail);
    }

    /** A page of the combined tree, its parent read off its path. */
    public SettingsPage(String path, String title, List<SettingsTopic> subtopics, List<Setting<?>> settings,
                        List<String> trail) {
        this(path, title, subtopics, settings, trail, null, slashParent(path));
    }

    private static String slashParent(String path) {
        if (path == null) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? null : path.substring(0, slash);
    }

    /** The path that opens one of this page's sections. */
    public String pathTo(SettingsTopic topic) {
        return scope == null ? topic.path() : scope + ":" + topic.path();
    }

    /** Whether this is the front page — the list of every plugin's categories. */
    public boolean isRoot() {
        return path == null;
    }

    /** Whether opening this leads further in rather than showing things to change. */
    public boolean isMenu() {
        return !subtopics.isEmpty();
    }

    /** Nothing to show at all. The GUI should not offer a way in. */
    public boolean isEmpty() {
        return subtopics.isEmpty() && settings.isEmpty();
    }

    /** How many windows this page needs: as many as the longer of its categories and its settings fill, at least one. */
    public int pageCount(int topicsPerPage, int settingsPerPage) {
        int forTopics = (subtopics.size() + topicsPerPage - 1) / topicsPerPage;
        int forSettings = (settings.size() + settingsPerPage - 1) / settingsPerPage;
        return Math.max(1, Math.max(forTopics, forSettings));
    }

    /** The categories shown on one window of this page. */
    public List<SettingsTopic> topicsOn(int window, int perPage) {
        return slice(subtopics, window, perPage);
    }

    /** The settings shown on one window of this page. */
    public List<Setting<?>> settingsOn(int window, int perPage) {
        return slice(settings, window, perPage);
    }

    private static <T> List<T> slice(List<T> all, int window, int perPage) {
        int from = Math.max(0, window) * perPage;
        return from >= all.size() ? List.of() : all.subList(from, Math.min(all.size(), from + perPage));
    }

    /** Where Back goes: the page above, or null when that is the front page. */
    public String parentPath() {
        return parent;
    }

    /**
     * A trail short enough for a window title.
     *
     * <p>Cut from the <em>left</em>: the end is where you are and the start is context you can get
     * back to by pressing Back, so dropping the start loses the least. A cut trail says so with an
     * ellipsis rather than silently pretending it started three levels down.
     */
    public static List<String> shortenTrail(List<String> full, int keep) {
        if (full == null || full.size() <= keep) {
            return full == null ? List.of() : List.copyOf(full);
        }
        List<String> shortened = new ArrayList<>();
        shortened.add("…");
        shortened.addAll(full.subList(full.size() - keep, full.size()));
        return List.copyOf(shortened);
    }
}
