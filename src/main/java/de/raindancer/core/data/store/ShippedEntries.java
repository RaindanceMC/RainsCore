package de.raindancer.core.data.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings a catalogue file a plugin wrote out once (roles.yml, quests.yml) up to what a newer version ships, without
 * undoing the owner's edits: entries the file has never seen are added, and an entry missing a field the shipped one
 * has gets that field. Text, not YAML — comments and everything the owner wrote stay as they are.
 *
 * <p>Entries are the two-space-indented keys under one top-level section ({@code roles:}); fields are the
 * four-space-indented keys of an entry. Which shipped entries the file has seen is kept in a comment line at its end,
 * so one the owner deleted stays deleted.
 */
public final class ShippedEntries {

    private static final String SEEN = "# shipped entries already offered: ";
    private static final Pattern ENTRY = Pattern.compile("^  ([A-Za-z0-9_-]+):\\s*$");
    private static final Pattern FIELD = Pattern.compile("^    ([A-Za-z0-9_-]+):");

    /** What changed. */
    public record Merged(String text, List<String> added, List<String> filled) {

        public boolean changed() {
            return !added.isEmpty() || !filled.isEmpty();
        }
    }

    private ShippedEntries() {
    }

    /**
     * Writes the shipped file out when there is none; otherwise merges what is new in it into the one there. The
     * file is only written when something was added or filled in, or it does not yet say what it has seen.
     *
     * @return what was added or filled in; empty when nothing was
     */
    public static Merged bringUp(java.nio.file.Path file, java.util.function.Supplier<java.io.InputStream> shipped,
                                 String section, Set<String> fields) {
        return bringUp(new YamlStore(file), shipped, section, fields);
    }

    /** As {@link #bringUp(java.nio.file.Path, java.util.function.Supplier, String, Set)}, into a store of the caller's scope. */
    public static Merged bringUp(YamlStore store, java.util.function.Supplier<java.io.InputStream> shipped,
                                 String section, Set<String> fields) {
        try (java.io.InputStream in = shipped.get()) {
            if (in == null) {
                return new Merged("", List.of(), List.of());
            }
            String offered = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            java.util.Optional<String> now = store.readText();
            if (now.isEmpty()) {
                store.writeTextIfAbsent(merge(offered, offered, section, fields).text());
                return new Merged("", List.of(), List.of());
            }
            Merged merged = merge(now.get(), offered, section, fields);
            if (merged.changed() || !now.get().contains(SEEN)) {
                store.writeText(merged.text());
            }
            return merged;
        } catch (java.io.IOException failed) {
            // Left as it is; the plugin reads what is there.
            return new Merged("", List.of(), List.of());
        }
    }

    /**
     * @param fields fields that are filled in on an existing entry missing them ("abilities"); others never are
     */
    public static Merged merge(String file, String shipped, String section, Set<String> fields) {
        Map<String, List<String>> ours = entries(file, section);
        Map<String, List<String>> theirs = entries(shipped, section);
        Set<String> seen = seen(file);
        boolean firstTime = seen.isEmpty();
        if (firstTime) {
            // Before this marker there was no telling a deleted entry from a new one; what the file has is what it saw.
            seen.addAll(ours.keySet());
        }
        List<String> lines = new ArrayList<>(List.of(file.split("\n", -1)));
        lines.removeIf(line -> line.startsWith(SEEN));
        List<String> added = new ArrayList<>();
        List<String> filled = new ArrayList<>();

        for (Map.Entry<String, List<String>> shippedEntry : theirs.entrySet()) {
            String id = shippedEntry.getKey();
            if (!ours.containsKey(id)) {
                continue;
            }
            Map<String, List<String>> have = fieldsOf(ours.get(id));
            Map<String, List<String>> offer = fieldsOf(shippedEntry.getValue());
            for (String field : fields) {
                if (!have.containsKey(field) && offer.containsKey(field)) {
                    int end = endOf(lines, section, id);
                    if (end >= 0) {
                        lines.addAll(end, offer.get(field));
                        filled.add(id + "." + field);
                    }
                }
            }
        }
        for (Map.Entry<String, List<String>> shippedEntry : theirs.entrySet()) {
            String id = shippedEntry.getKey();
            if (ours.containsKey(id) || seen.contains(id)) {
                continue;
            }
            int end = endOfSection(lines, section);
            List<String> block = new ArrayList<>();
            block.add("");
            block.addAll(shippedEntry.getValue());
            lines.addAll(end, block);
            added.add(id);
        }
        seen.addAll(theirs.keySet());
        while (!lines.isEmpty() && lines.getLast().isBlank()) {
            lines.removeLast();
        }
        lines.add("");
        lines.add(SEEN + String.join(", ", seen));
        lines.add("");
        return new Merged(String.join("\n", lines), added, filled);
    }

    private static Set<String> seen(String file) {
        Set<String> seen = new LinkedHashSet<>();
        for (String line : file.split("\n")) {
            if (line.startsWith(SEEN)) {
                for (String id : line.substring(SEEN.length()).split(",")) {
                    if (!id.isBlank()) {
                        seen.add(id.strip());
                    }
                }
            }
        }
        return seen;
    }

    /** Each entry's lines, from its key to its last non-blank, non-comment line. */
    static Map<String, List<String>> entries(String text, String section) {
        Map<String, List<String>> entries = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);
        int start = sectionStart(lines, section);
        if (start < 0) {
            return entries;
        }
        String current = null;
        List<String> block = null;
        for (int i = start + 1; i < lines.length; i++) {
            String line = lines[i];
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                break;
            }
            Matcher entry = ENTRY.matcher(line);
            if (entry.matches()) {
                if (current != null) {
                    entries.put(current, trim(block));
                }
                current = entry.group(1);
                block = new ArrayList<>();
                block.add(line);
            } else if (block != null) {
                block.add(line);
            }
        }
        if (current != null) {
            entries.put(current, trim(block));
        }
        return entries;
    }

    private static Map<String, List<String>> fieldsOf(List<String> entry) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        String current = null;
        for (String line : entry.subList(1, entry.size())) {
            Matcher field = FIELD.matcher(line);
            if (field.find()) {
                current = field.group(1);
                fields.put(current, new ArrayList<>());
            }
            if (current != null) {
                fields.get(current).add(line);
            }
        }
        fields.replaceAll((key, value) -> trim(value));
        return fields;
    }

    private static List<String> trim(List<String> block) {
        int end = block.size();
        while (end > 1 && (block.get(end - 1).isBlank() || block.get(end - 1).strip().startsWith("#"))) {
            end--;
        }
        return new ArrayList<>(block.subList(0, end));
    }

    private static int sectionStart(String[] lines, String section) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].matches("^" + Pattern.quote(section) + ":\\s*$")) {
                return i;
            }
        }
        return -1;
    }

    /** The index just after an entry's last line. */
    private static int endOf(List<String> lines, String section, String id) {
        int start = sectionStart(lines.toArray(String[]::new), section);
        int at = -1;
        for (int i = start + 1; start >= 0 && i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                break;
            }
            Matcher entry = ENTRY.matcher(line);
            if (entry.matches()) {
                if (at >= 0) {
                    break;
                }
                if (entry.group(1).equals(id)) {
                    at = i;
                }
            }
        }
        if (at < 0) {
            return -1;
        }
        int last = at;
        for (int i = at + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (ENTRY.matcher(line).matches() || !line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                break;
            }
            if (!line.isBlank() && !line.strip().startsWith("#")) {
                last = i;
            }
        }
        return last + 1;
    }

    /** The index just after the section's last entry line. */
    private static int endOfSection(List<String> lines, String section) {
        int start = sectionStart(lines.toArray(String[]::new), section);
        if (start < 0) {
            lines.add(section + ":");
            return lines.size();
        }
        int last = start;
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                break;
            }
            if (!line.isBlank() && !line.strip().startsWith("#")) {
                last = i;
            }
        }
        return last + 1;
    }
}
