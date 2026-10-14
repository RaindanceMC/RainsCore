package de.raindancer.core.data.store;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Merging two writers' versions of one YAML document, value by value.
 *
 * <p>On a network two servers can both change a document between reading and writing it. Writing whole
 * documents, the later one silently undoes the earlier. Merging what each of them actually changed
 * keeps both whenever they touched different values — which, for documents keyed by player or entry,
 * is nearly always. A value both changed is a real conflict: the newer write wins and the path is named
 * so it can be logged.
 *
 * <p>A value is a leaf: a scalar or a whole list. Lists are never interleaved — half of one writer's
 * list and half of the other's is a list neither of them meant.
 */
public final class YamlMerge {

    /** The merged document, and the paths both sides changed differently. */
    public record Result(YamlConfiguration yaml, List<String> conflicts) {
    }

    private YamlMerge() {
    }

    /**
     * {@code theirs} with every change from {@code base} to {@code mine} applied on top.
     *
     * @param base   what this writer last read
     * @param mine   what this writer wants it to be now
     * @param theirs what is stored now, written by somebody else since {@code base}
     */
    public static Result threeWay(YamlConfiguration base, YamlConfiguration mine, YamlConfiguration theirs) {
        Map<String, Object> was = leaves(base);
        Map<String, Object> now = leaves(mine);
        Map<String, Object> other = leaves(theirs);
        YamlConfiguration merged = copy(theirs);
        List<String> conflicts = new ArrayList<>();

        for (Map.Entry<String, Object> changed : now.entrySet()) {
            String path = changed.getKey();
            Object before = was.get(path);
            if (Objects.equals(before, changed.getValue())) {
                continue;
            }
            Object theirsNow = other.get(path);
            if (!Objects.equals(theirsNow, before) && !Objects.equals(theirsNow, changed.getValue())) {
                conflicts.add(path);
            }
            setLeaf(merged, path, changed.getValue());
        }
        for (Map.Entry<String, Object> gone : was.entrySet()) {
            String path = gone.getKey();
            if (now.containsKey(path) || isUnderALeaf(path, now) || hadChildren(path, now)) {
                // Still there, or replaced by a value above it, or turned into a section in mine: not removed.
                continue;
            }
            Object theirsNow = other.get(path);
            if (theirsNow != null && !Objects.equals(theirsNow, gone.getValue())) {
                conflicts.add(path);
            }
            merged.set(path, null);
        }
        pruneEmptySections(merged, was, now);
        return new Result(merged, List.copyOf(conflicts));
    }

    /**
     * A file being imported into a document that already has content: everything the file has that the
     * document lacks is added; where both have a value, the document's is kept and the path named.
     */
    public static Result importInto(YamlConfiguration file, YamlConfiguration stored) {
        Map<String, Object> incoming = leaves(file);
        Map<String, Object> existing = leaves(stored);
        YamlConfiguration merged = copy(stored);
        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<String, Object> each : incoming.entrySet()) {
            String path = each.getKey();
            if (existing.containsKey(path)) {
                if (!Objects.equals(existing.get(path), each.getValue())) {
                    conflicts.add(path);
                }
                continue;
            }
            if (isUnderALeaf(path, existing) || merged.isSet(path) && !merged.isConfigurationSection(path)) {
                conflicts.add(path);
                continue;
            }
            setLeaf(merged, path, each.getValue());
        }
        return new Result(merged, List.copyOf(conflicts));
    }

    // ---------------------------------------------------------------------------- internals

    /** Every value of the document by its full path; a list is one value. */
    static Map<String, Object> leaves(ConfigurationSection yaml) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (yaml == null) {
            return out;
        }
        for (String path : yaml.getKeys(true)) {
            if (yaml.isConfigurationSection(path)) {
                ConfigurationSection section = yaml.getConfigurationSection(path);
                if (section != null && section.getKeys(false).isEmpty()) {
                    // An empty section is a value too: "this player has no homes" is not "this player is unknown".
                    out.put(path, Map.of());
                }
                continue;
            }
            out.put(path, normalise(yaml.get(path)));
        }
        return out;
    }

    private static Object normalise(Object value) {
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(each -> copy.add(normalise(each)));
            return copy;
        }
        if (value instanceof ConfigurationSection section) {
            return section.getValues(true);
        }
        return value;
    }

    private static void setLeaf(YamlConfiguration yaml, String path, Object value) {
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            yaml.set(path, null);
            yaml.createSection(path);
            return;
        }
        // A value is going where a section of the other writer's is: the value replaces it whole.
        yaml.set(path, value);
    }

    /** Whether some proper prefix of {@code path} is itself a value in {@code leaves} — it became a scalar there. */
    private static boolean isUnderALeaf(String path, Map<String, Object> leaves) {
        int dot = path.lastIndexOf('.');
        while (dot > 0) {
            String parent = path.substring(0, dot);
            Object value = leaves.get(parent);
            if (value != null && !(value instanceof Map<?, ?> map && map.isEmpty())) {
                return true;
            }
            dot = parent.lastIndexOf('.');
        }
        return false;
    }

    /** A section this writer emptied by removing what was under it goes too, unless it is empty in mine on purpose. */
    private static void pruneEmptySections(YamlConfiguration merged, Map<String, Object> was, Map<String, Object> now) {
        List<String> paths = new ArrayList<>(merged.getKeys(true));
        paths.sort((left, right) -> Integer.compare(right.length(), left.length()));
        for (String path : paths) {
            if (merged.isConfigurationSection(path)) {
                ConfigurationSection section = merged.getConfigurationSection(path);
                boolean emptiedByMe = section != null && section.getKeys(false).isEmpty()
                        && !now.containsKey(path) && hadChildren(path, was);
                if (emptiedByMe) {
                    merged.set(path, null);
                }
            }
        }
    }

    private static boolean hadChildren(String path, Map<String, Object> was) {
        String prefix = path + ".";
        return was.keySet().stream().anyMatch(each -> each.startsWith(prefix));
    }

    private static YamlConfiguration copy(YamlConfiguration source) {
        YamlConfiguration copy = new YamlConfiguration();
        if (source == null) {
            return copy;
        }
        try {
            copy.loadFromString(source.saveToString());
        } catch (org.bukkit.configuration.InvalidConfigurationException impossible) {
            throw new IllegalStateException("A document that was just saved could not be read back", impossible);
        }
        return copy;
    }
}
