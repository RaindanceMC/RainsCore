package de.raindancer.core.ui.help;

import de.raindancer.core.data.settings.Setting;
import de.raindancer.core.data.settings.SettingsSchema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A module's guide, checked by its own build: every file reads, every setting it quotes exists, every placeholder
 * fills in, every link goes somewhere — and every command the module has is explained in some topic.
 *
 * <pre>{@code
 * @Test
 * void theGuideIsComplete() {
 *     assertThat(HelpLint.of(Path.of("src/main/resources/rains-help"))
 *             .module("economy")
 *             .settings(EconomySettings.class, EconomySettings.DEFAULTS)
 *             .commands(new EconomyModule().commands().stream().map(ModuleCommand::name).toList())
 *             .problems()).isEmpty();
 * }
 * }</pre>
 *
 * <p>The settings are what makes the guide honest on a server that changed them: a page that says "5 %" in
 * prose is wrong the day the tax changes, and one that says {@code {setting:economy:pay.tax-percent|percent}} is
 * not — as long as the key is right, which is what this checks. A typo would otherwise reach players as "?".
 */
public final class HelpLint {

    private final Path directory;
    private final Map<String, Object> settings = new HashMap<>();
    private final Set<String> schemaIds = new LinkedHashSet<>();
    private final Set<String> foreign = new LinkedHashSet<>();
    private final Set<String> facts = new LinkedHashSet<>();
    private final Set<String> otherTopics = new LinkedHashSet<>();
    private final Set<String> commands = new LinkedHashSet<>();
    private final Set<String> undocumented = new LinkedHashSet<>();
    private String module;

    private HelpLint(Path directory) {
        this.directory = directory;
    }

    /** The folder holding the module's topic files — {@code src/main/resources/rains-help}. */
    public static HelpLint of(Path directory) {
        return new HelpLint(directory);
    }

    /** Every topic must name this module, so it disappears where the module is not running. */
    public HelpLint module(String id) {
        this.module = id.toLowerCase(Locale.ROOT);
        return this;
    }

    /** A settings record of this module, read at its defaults. */
    public <T> HelpLint settings(Class<T> type, T defaults) {
        SettingsSchema<T> schema = SettingsSchema.of(type, defaults);
        schemaIds.add(schema.id());
        for (Setting<?> setting : schema.settings()) {
            Object value = setting.valueIn(defaults);
            settings.put(schema.id() + ":" + setting.key(), value == null ? "" : value);
        }
        return this;
    }

    /**
     * Settings of other plugins this guide quotes ({@code {setting:economy:…}} in a shop's guide), which this build
     * cannot see. Any key under these ids is taken on trust — and checked by {@code /help check} on a real server.
     */
    public HelpLint foreignSettings(String... ids) {
        for (String id : ids) {
            foreign.add(id.toLowerCase(Locale.ROOT));
        }
        return this;
    }

    /** Facts the module registers with {@link HelpFacts}. */
    public HelpLint facts(String... names) {
        facts.addAll(List.of(names));
        return this;
    }

    /** Topics of other plugins this guide links to or lists as related. */
    public HelpLint otherTopics(String... ids) {
        for (String id : ids) {
            otherTopics.add(id.toLowerCase(Locale.ROOT));
        }
        return this;
    }

    /** The module's commands. Each must be in some topic's {@code commands:} — or in {@link #undocumented}. */
    public HelpLint commands(Collection<String> names) {
        names.forEach(name -> commands.add(name.toLowerCase(Locale.ROOT)));
        return this;
    }

    /** Commands deliberately in no topic: callbacks nobody types, internal tools. Say why in the test. */
    public HelpLint undocumented(String... names) {
        for (String name : names) {
            undocumented.add(name.toLowerCase(Locale.ROOT));
        }
        return this;
    }

    /** Everything wrong, one line each. Empty is a complete guide. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            problems.add(directory + " does not exist — a module's guide lives in src/main/resources/rains-help");
            return problems;
        }
        List<HelpTopic> topics = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".yml")).sorted().toList()) {
                String name = file.getFileName().toString();
                var parsed = HelpTopicParser.parse(Files.readString(file, StandardCharsets.UTF_8), name);
                problems.addAll(parsed.problems());
                parsed.topic().ifPresent(topic -> {
                    if (!name.equals(topic.id() + ".yml")) {
                        problems.add(name + ": its id is '" + topic.id() + "', so the file should be called "
                                + topic.id() + ".yml");
                    }
                    topics.add(topic);
                });
            }
        } catch (IOException unreadable) {
            problems.add(directory + " could not be read: " + unreadable.getMessage());
            return problems;
        }
        if (topics.isEmpty() && problems.isEmpty()) {
            problems.add(directory + " holds no topics");
        }

        Map<String, String> titles = new HashMap<>();
        otherTopics.forEach(id -> titles.put(id, id));
        topics.forEach(topic -> titles.put(topic.id(), topic.title()));

        Map<String, Object> known = new HashMap<>(settings);
        Map<String, String> factValues = new HashMap<>();
        facts.forEach(fact -> factValues.put(fact, "12"));
        HelpContexts.Fixed context = HelpContexts.fixedWith(key -> standIn(known, key))
                .withFacts(factValues).withTopics(titles).seeingEverything();

        Set<String> covered = new LinkedHashSet<>();
        for (HelpTopic topic : topics) {
            String where = topic.id() + ".yml";
            if (module != null && !module.equals(topic.module())) {
                problems.add(where + ": needs 'module: " + module + "', so it is hidden where the module is off");
            }
            covered.addAll(topic.commands());
            for (String command : topic.commands()) {
                if (!commands.isEmpty() && !commands.contains(command)) {
                    problems.add(where + ": lists /" + command + ", which is not one of this module's commands");
                }
            }
            for (String related : topic.related()) {
                if (!titles.containsKey(related)) {
                    problems.add(where + ": related topic '" + related + "' is neither here nor in otherTopics(...)");
                }
            }
            List<String> texts = new ArrayList<>(List.of(topic.summary(), topic.intro()));
            List<String> conditions = new ArrayList<>();
            if (topic.when() != null) {
                conditions.add(topic.when().toString());
            }
            for (HelpSection section : topic.sections()) {
                texts.add(section.text());
                if (section.when() != null) {
                    conditions.add(section.when().toString());
                }
                String plain = HelpText.plain(HelpText.render(section.text(), context, new ArrayList<>()));
                if (plain.length() > 1_800) {
                    problems.add(where + ": the answer to '" + section.question() + "' is " + plain.length()
                            + " characters — a wall; split it into two questions");
                }
            }
            for (String condition : conditions) {
                for (String key : HelpExpression.parse(condition).settings()) {
                    checkKey(where, key, problems);
                }
            }
            for (String text : texts) {
                for (String key : HelpText.settingsRead(text)) {
                    checkKey(where, key, problems);
                }
                List<String> found = new ArrayList<>();
                HelpText.render(text, context, found);
                for (String problem : found) {
                    if (!problem.startsWith("the setting '")) {
                        problems.add(where + ": " + problem);
                    }
                }
            }
        }
        for (String command : commands) {
            if (!covered.contains(command) && !undocumented.contains(command)) {
                problems.add("/" + command + " is in no topic's commands — explain it, or mark it undocumented(\""
                        + command + "\") with a reason");
            }
        }
        return problems.stream().distinct().toList();
    }

    private void checkKey(String where, String key, List<String> problems) {
        int colon = key.indexOf(':');
        if (colon < 0) {
            problems.add(where + ": '" + key + "' — write settings as plugin:key; a bare key reaches whichever "
                    + "plugin declared it first");
            return;
        }
        String id = key.substring(0, colon).toLowerCase(Locale.ROOT);
        if (foreign.contains(id)) {
            return;
        }
        if (!settings.containsKey(key)) {
            problems.add(where + ": the setting '" + key + "' does not exist" + (schemaIds.contains(id) ? ""
                    : " (settings of '" + id + "' are not known here — pass its record, or foreignSettings(\"" + id
                    + "\"))"));
        }
    }

    /** A known key's default; any key of a foreign plugin a stand-in, so a guide quoting another still renders. */
    private java.util.Optional<Object> standIn(Map<String, Object> known, String key) {
        Object value = known.get(key);
        if (value != null) {
            return java.util.Optional.of(value);
        }
        int colon = key.indexOf(':');
        if (colon > 0 && foreign.contains(key.substring(0, colon).toLowerCase(Locale.ROOT))) {
            return java.util.Optional.of("1");
        }
        return java.util.Optional.empty();
    }
}
