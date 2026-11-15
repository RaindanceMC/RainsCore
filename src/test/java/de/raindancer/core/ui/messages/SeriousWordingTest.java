package de.raindancer.core.ui.messages;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * messages-serious.yml was a second wording of the same lines. There is one tone now, so it stays empty;
 * should a line come back, it must not drift from messages.yml: a key that no longer exists says
 * nothing, and a placeholder that went missing drops a name out of a sentence a player needed.
 */
class SeriousWordingTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("<([a-z][a-z0-9-]*)>");
    private static final Set<String> FORMATS = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold",
            "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "bold", "italic", "underlined", "strikethrough", "obfuscated", "u");

    private static Map<String, Object> flat(String path) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(path));
        Map<String, Object> out = new LinkedHashMap<>();
        walk(yaml, "", out);
        return out;
    }

    private static void walk(ConfigurationSection section, String prefix, Map<String, Object> out) {
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection child) {
                walk(child, prefix + key + ".", out);
            } else {
                out.put(prefix + key, value);
            }
        }
    }

    private static Set<String> placeholders(Object value) {
        Set<String> found = new TreeSet<>();
        for (Object line : value instanceof List<?> list ? list : List.of(value)) {
            Matcher matcher = PLACEHOLDER.matcher(String.valueOf(line));
            while (matcher.find()) {
                if (!FORMATS.contains(matcher.group(1))) {
                    found.add(matcher.group(1));
                }
            }
        }
        return found;
    }

    private final Map<String, Object> playful = flat("src/main/resources/messages.yml");
    private final Map<String, Object> serious = flat("src/main/resources/messages-serious.yml");

    @Test
    @DisplayName("there is one tone only: the serious file is there but has no lines")
    void oneToneOnly() {
        assertThat(new File("src/main/resources/messages-serious.yml")).exists();
        assertThat(serious).isEmpty();
    }

    @Test
    @DisplayName("every serious line is a line messages.yml has, and actually differs from it")
    void sameKeys() {
        List<String> strays = new ArrayList<>();
        List<String> identical = new ArrayList<>();
        serious.forEach((key, value) -> {
            if (!playful.containsKey(key)) {
                strays.add(key);
            } else if (playful.get(key).equals(value)) {
                identical.add(key);
            }
        });
        assertThat(strays).as("keys messages.yml does not have").isEmpty();
        assertThat(identical).as("lines identical to messages.yml, which only make the file longer").isEmpty();
    }

    @Test
    @DisplayName("a serious line carries exactly the placeholders of its playful one")
    void samePlaceholders() {
        List<String> drifted = new ArrayList<>();
        serious.forEach((key, value) -> {
            Object other = playful.get(key);
            if (other != null && !placeholders(other).equals(placeholders(value))) {
                drifted.add(key + ": " + placeholders(other) + " vs " + placeholders(value));
            }
        });
        assertThat(drifted).isEmpty();
    }
}
