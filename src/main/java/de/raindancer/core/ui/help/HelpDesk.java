package de.raindancer.core.ui.help;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsRegistry;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.hub.Hub;
import de.raindancer.core.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The guide's memory: every topic on this server, kept current as plugins come and go, and the owner's own
 * topics from {@code plugins/RainsCore/help.yml}.
 *
 * <h2>The owner's file</h2>
 * {@code help.yml} holds topics in the same shape as a plugin's {@code rains-help/<id>.yml}, under {@code topics:},
 * keyed by id — a new id adds a topic ("rules", "voting"), a shipped id replaces that topic whole. {@code hidden:}
 * lists ids the guide should not offer at all. Like every Core document it lives in the database from Core 2.0;
 * {@code /documents} exports it to edit by hand, and {@code /help reload} reads it again.
 */
public final class HelpDesk {

    private static final LogChannel log = Log.of("help");

    private static volatile HelpDesk installed;

    private final Plugin core;
    private final SettingsRegistry registry;
    private final SettingsStore<HelpSettings> settings;
    private final YamlStore overrides;
    /** The owner's help.yml as last read — off the server's threads, since it lives in the database. */
    private volatile YamlConfiguration ownersPages = new YamlConfiguration();
    private volatile boolean ownersPagesReadable = true;
    private volatile HelpLibrary library;

    public HelpDesk(Plugin core, SettingsRegistry registry, SettingsStore<HelpSettings> settings, Path dataFolder) {
        this.core = core;
        this.registry = registry;
        this.settings = settings;
        this.overrides = new YamlStore(dataFolder.resolve("help.yml"));
    }

    /**
     * Reads the owner's help.yml (writing the commented example the first time) and forgets the library. Reaches the
     * database, so never on a server thread: Core calls it from {@code Scheduling.async}, at start and on reload.
     */
    public void readOwnersPages() {
        overrides.writeTextIfAbsent(EXAMPLE);
        var read = overrides.readIfReadable();
        ownersPagesReadable = read.isPresent();
        ownersPages = read.orElseGet(YamlConfiguration::new);
        invalidate();
    }

    /** Reads everything again off the server's threads, then tells {@code done} what the guide knows now. */
    public void reloadAsync(java.util.function.Consumer<HelpLibrary> done) {
        de.raindancer.core.platform.util.Scheduling.async(core, () -> {
            readOwnersPages();
            HelpLibrary built = library();
            if (done != null) {
                done.accept(built);
            }
        });
    }

    /** The desk Core runs, once it has started. */
    public static Optional<HelpDesk> current() {
        return Optional.ofNullable(installed);
    }

    public static void install(HelpDesk desk) {
        installed = desk;
    }

    public Plugin plugin() {
        return core;
    }

    public HelpSettings settings() {
        return settings.current();
    }

    /** Every topic, read the first time somebody asks after plugins changed. */
    public HelpLibrary library() {
        HelpLibrary current = library;
        if (current == null) {
            synchronized (this) {
                current = library;
                if (current == null) {
                    current = build();
                    library = current;
                }
            }
        }
        return current;
    }

    /** Forgets the library; the next question reads every plugin's files again. A plugin came or went. */
    public void invalidate() {
        library = null;
    }

    /** This server, for this reader. */
    public HelpContext contextFor(CommandSender reader) {
        HelpLibrary current = library();
        LiveHelpContext[] self = new LiveHelpContext[1];
        self[0] = new LiveHelpContext(reader, registry, id -> current.byId(id)
                .filter(topic -> HelpLibrary.visible(topic, self[0])).map(HelpTopic::title));
        return self[0];
    }

    /**
     * Everything wrong with the guide on this server: what loading found, and every placeholder and condition that
     * does not fill in here — read as somebody holding every permission, for every topic whose module is running.
     */
    public List<String> check(CommandSender reader) {
        HelpLibrary current = library();
        List<String> problems = new ArrayList<>(current.problems());
        HelpContext live = contextFor(reader);
        HelpContext everything = new SeeingEverything(live);
        for (HelpTopic topic : current.all()) {
            if (topic.module() != null && !live.hasModule(topic.module())) {
                continue;
            }
            List<String> texts = new ArrayList<>(List.of(topic.summary(), topic.intro()));
            topic.sections().forEach(section -> texts.add(section.text()));
            for (String text : texts) {
                List<String> found = new ArrayList<>();
                HelpText.render(text, everything, found);
                found.stream()
                        // A link to a topic that exists but is switched off here reads as plain words — not a fault.
                        .filter(problem -> !(problem.startsWith("the topic '") && current.byId(
                                problem.substring("the topic '".length(), problem.indexOf('\'', "the topic '".length())))
                                .isPresent()))
                        .forEach(problem -> problems.add(topic.plugin() + " / " + topic.id() + ": " + problem));
            }
        }
        return problems.stream().distinct().toList();
    }

    private HelpLibrary build() {
        List<String> problems = new ArrayList<>();
        List<Plugin> enabled = Arrays.stream(Bukkit.getPluginManager().getPlugins()).filter(Plugin::isEnabled).toList();
        List<HelpTopic> shipped = new ArrayList<>();
        for (HelpSources.Shipped file : HelpSources.read(enabled, problems)) {
            var parsed = HelpTopicParser.parse(file.yaml(), file.plugin() + ": " + file.name());
            problems.addAll(parsed.problems());
            parsed.topic().ifPresent(topic -> shipped.add(topic.from(file.plugin())));
        }

        List<HelpTopic> owners = new ArrayList<>();
        Set<String> hidden = new LinkedHashSet<>();
        if (!ownersPagesReadable) {
            problems.add("help.yml could not be read — the owner's topics are left out until it is fixed");
        }
        YamlConfiguration file = ownersPages;
        ConfigurationSection topics = file.getConfigurationSection("topics");
        if (topics != null) {
            for (String id : topics.getKeys(false)) {
                ConfigurationSection topic = topics.getConfigurationSection(id);
                if (topic == null) {
                    problems.add("help.yml: topics." + id + " is not a topic");
                    continue;
                }
                if (!topic.contains("id")) {
                    topic.set("id", id);
                }
                HelpTopicParser.fromSection(topic, "help.yml", problems)
                        .topic().ifPresent(found -> owners.add(found.from("help.yml")));
            }
        }
        file.getStringList("hidden").forEach(hidden::add);

        List<HelpLibrary.Feature> features = new ArrayList<>();
        if (RainsCore.isAvailable()) {
            var directory = RainsCore.get().commands();
            for (HubEntry entry : RainsCore.get().hub().all()) {
                features.add(new HelpLibrary.Feature(entry.id(), entry.name(), entry.description(),
                        Hub.notesFor(entry, directory, any -> true)));
            }
        }
        HelpLibrary built = HelpLibrary.of(shipped, owners, hidden, features, problems);
        log.info("The guide knows {} topic(s){}.", built.all().size(),
                built.problems().isEmpty() ? "" : " — " + built.problems().size() + " problem(s), see /help check");
        return built;
    }

    /** The live server with every permission held — what {@link #check} reads through. */
    private record SeeingEverything(HelpContext live) implements HelpContext {
        @Override
        public Optional<Object> setting(String key) {
            return live.setting(key);
        }

        @Override
        public Optional<String> display(String key) {
            return live.display(key);
        }

        @Override
        public boolean has(String permission) {
            return true;
        }

        @Override
        public boolean hasPlugin(String name) {
            return live.hasPlugin(name);
        }

        @Override
        public boolean hasModule(String id) {
            return live.hasModule(id);
        }

        @Override
        public Optional<String> fact(String name) {
            return live.fact(name);
        }

        @Override
        public Optional<String> money(String amount) {
            return live.money(amount);
        }

        @Override
        public Optional<String> topicTitle(String id) {
            return live.topicTitle(id);
        }

        @Override
        public String serverName() {
            return live.serverName();
        }

        @Override
        public String readerName() {
            return live.readerName();
        }
    }

    private static final String EXAMPLE = """
            # ─────────────────────────────────────────────────────────────────────────────
            #  The guide's own pages — what /help and the guide at spawn explain beyond
            #  what the plugins ship.
            #
            #  A topic here with a NEW id adds a page ("rules", "voting", "staff-team").
            #  One with the id of a shipped topic REPLACES it whole (/help check lists ids).
            #  hidden: lists ids the guide should not offer at all.
            #
            #  Text is MiniMessage. Placeholders fill in live:
            #    {setting:economy:pay.tax-percent|percent}   a setting, as it is now
            #    {money:500}  {player}  {server}  {cmd:/spawn}  {topic:economy}
            #    {if:economy:features.pay}…{else}…{/if}
            #  A section's 'when:' shows it only while the condition holds; 'permission:'
            #  only to those holding it. After editing: /help reload, then /help check.
            # ─────────────────────────────────────────────────────────────────────────────
            topics: {}
            #  rules:
            #    title: Server rules
            #    summary: What is allowed here, and what is not.
            #    category: Getting started
            #    icon: WRITABLE_BOOK
            #    sections:
            #      - question: What are the rules?
            #        text: |
            #          <white>1.</white> Be kind. <white>2.</white> No cheating. <white>3.</white> No griefing.
            hidden: []
            """;
}
