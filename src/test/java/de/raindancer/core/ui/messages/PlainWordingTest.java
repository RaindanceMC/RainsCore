package de.raindancer.core.ui.messages;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Everything a player or an owner reads is said plainly and in one tone: no dashes standing in for
 * punctuation, no emoji as decoration, no shouting, no chatbot cheer. Covers the wording file, the
 * /help guide, paper-plugin.yml and the string literals in the sources (menus, lore, logs).
 */
class PlainWordingTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Path SOURCES = Path.of("src/main/java");

    static final Pattern DASH = Pattern.compile("[–—]|(?<=[A-Za-z>)]) -{1,2} (?=[A-Za-z<(])");
    static final Pattern EMOJI = Pattern.compile("[\\x{1F000}-\\x{1FAFF}✨✅❌]");
    /** Not a regex's (?! nor a character class like [_!?]. */
    static final Pattern SHOUT = Pattern.compile("(?<![(_+\\\\-])[!?]{2,}");
    static final Pattern CHATBOT = Pattern.compile(
            "(?i)\\b(oops|whoops|woohoo|hooray|yay|awesome|amazing|let's|have fun|enjoy|boom"
                    + "|not just|not only|not merely)\\b");

    /** Help keys holding ids, search words and command names; {cmd:/boom} is a command, not cheer. */
    private static final Pattern HELP_KEYS = Pattern.compile("^(aliases|keywords|commands|related|id|icon|category)(\\[\\d+])?$");
    private static final Pattern COMMAND_SYNTAX = Pattern.compile("\\{(cmd|run):[^}]*}");

    private static final Pattern LITERAL = Pattern.compile("\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"", Pattern.DOTALL);

    static List<String> problems(String text, boolean phrases) {
        List<String> found = new ArrayList<>();
        if (DASH.matcher(text).find()) found.add("dash");
        if (EMOJI.matcher(text).find()) found.add("emoji");
        if (SHOUT.matcher(text).find()) found.add("!!/?!");
        if (phrases) {
            Matcher phrase = CHATBOT.matcher(text);
            if (phrase.find()) found.add("'" + phrase.group() + "'");
        }
        return found;
    }

    private static Object yaml(Path file) throws IOException {
        return new Yaml().load(Files.readString(file));
    }

    private static void flat(Object node, String key, List<Map.Entry<String, String>> into) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> flat(v, key.isEmpty() ? String.valueOf(k) : key + "." + k, into));
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) flat(list.get(i), key + "[" + i + "]", into);
        } else if (node instanceof String text) {
            into.add(Map.entry(key, text));
        }
    }

    private static List<String> scanYaml(Path file, boolean phrases) throws IOException {
        List<Map.Entry<String, String>> lines = new ArrayList<>();
        flat(yaml(file), "", lines);
        assertThat(lines).as("%s has no lines, so this test reads nothing", file).isNotEmpty();
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, String> line : lines) {
            List<String> problems = new ArrayList<>(problems(line.getValue(), false));
            if (phrases && !HELP_KEYS.matcher(line.getKey()).find()) {
                problems(COMMAND_SYNTAX.matcher(line.getValue()).replaceAll(""), true).stream()
                        .filter(problem -> problem.startsWith("'")).forEach(problems::add);
            }
            for (String problem : problems) {
                found.add(file.getFileName() + ": " + line.getKey() + ": " + problem + ": " + line.getValue().strip());
            }
        }
        return found;
    }

    /** String literals with their line, comments stripped (line breaks kept so the numbers stay right). */
    static List<Map.Entry<Integer, String>> literals(String source) {
        String code = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(source)
                .replaceAll(block -> "\n".repeat((int) block.group().chars().filter(c -> c == '\n').count()));
        code = code.replaceAll("(?m)//[^\\n\"]*$", "").replaceAll("(?m)^\\s*//.*$", "");
        List<Map.Entry<Integer, String>> found = new ArrayList<>();
        Matcher literal = LITERAL.matcher(code);
        while (literal.find()) {
            int line = 1 + (int) code.substring(0, literal.start()).chars().filter(c -> c == '\n').count();
            found.add(Map.entry(line, literal.group(1) != null ? literal.group(1) : literal.group(2)));
        }
        return found;
    }

    @Test
    @DisplayName("the rules catch what they are for and leave regexes alone")
    void rulesWork() {
        assertThat(problems("Saved — welcome back", false)).containsExactly("dash");
        assertThat(problems("Saved - welcome back", false)).containsExactly("dash");
        assertThat(problems("Done!! 🎉", false)).containsExactly("emoji", "!!/?!");
        assertThat(problems("Oops, that did not work.", true)).containsExactly("'Oops'");
        assertThat(problems("(?![a-z])", false)).isEmpty();
        assertThat(problems("Range 1-10, a well-known e-mail.", true)).isEmpty();
        assertThat(problems(COMMAND_SYNTAX.matcher("{cmd:/boom} blows up").replaceAll(""), true)).isEmpty();
        assertThat(HELP_KEYS.matcher("aliases[2]").find()).isTrue();
        assertThat(literals("// \"Saved — no\"\nString s = \"Saved.\"; /* \"— no\" */"))
                .containsExactly(Map.entry(2, "Saved."));
    }

    @Test
    @DisplayName("messages.yml says it plainly")
    void messages() throws IOException {
        assertThat(scanYaml(RESOURCES.resolve("messages.yml"), true)).isEmpty();
    }

    @Test
    @DisplayName("there is one tone only, so messages-serious.yml has no lines")
    void oneTone() throws IOException {
        assertThat(yaml(RESOURCES.resolve("messages-serious.yml")))
                .as("messages.yml already is the plain wording; a second one would drift from it")
                .isNull();
    }

    @Test
    @DisplayName("the /help guide says it plainly")
    void help() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(RESOURCES.resolve("rains-help"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yml")).sorted().toList()) {
                found.addAll(scanYaml(file, true));
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("paper-plugin.yml has no dashes, emoji or shouting")
    void pluginYml() throws IOException {
        assertThat(scanYaml(RESOURCES.resolve("paper-plugin.yml"), false)).isEmpty();
    }

    @Test
    @DisplayName("no string in the sources has a dash, emoji or shouting")
    void sources() throws IOException {
        List<String> found = new ArrayList<>();
        int files = 0;
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            for (Path file : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                files++;
                for (Map.Entry<Integer, String> literal : literals(Files.readString(file))) {
                    for (String problem : problems(literal.getValue(), false)) {
                        found.add(SOURCES.relativize(file) + ":" + literal.getKey() + ": " + problem
                                + ": \"" + literal.getValue().strip() + "\"");
                    }
                }
            }
        }
        assertThat(files).isGreaterThan(100);
        assertThat(found).isEmpty();
    }
}
