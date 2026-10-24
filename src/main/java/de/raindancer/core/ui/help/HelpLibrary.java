package de.raindancer.core.ui.help;

import de.raindancer.core.platform.command.CommandNote;
import de.raindancer.core.platform.util.Closest;
import de.raindancer.core.ui.text.WordSearch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every topic on this server, and the questions asked of all of them at once: which a reader may see, which one
 * {@code /help shop} means, and what {@code /help search how do i sell} finds.
 *
 * <p>Built from what plugins ship, what the server's owner overrode, and — for every installed feature that shipped
 * no guide — a topic generated from its commands, so that "help for every plugin" holds on the day a new plugin
 * is installed and not on the day somebody writes its guide.
 *
 * <p>Immutable: a new library is built when plugins come or go, and a reader holds whichever was current.
 */
public final class HelpLibrary {

    /** An installed feature, as Core's hub knows it — what a generated topic is made from. */
    public record Feature(String id, String name, String description, List<CommandNote> commands) {
        public Feature {
            commands = commands == null ? List.of() : List.copyOf(commands);
        }
    }

    /** One search result: a topic, the question in it that matched best (or null), and how well. */
    public record Hit(HelpTopic topic, HelpSection section, int score) {
    }

    private final Map<String, HelpTopic> topics;
    private final List<String> problems;

    private HelpLibrary(Map<String, HelpTopic> topics, List<String> problems) {
        this.topics = topics;
        this.problems = problems;
    }

    /**
     * @param shipped   every topic plugins shipped, in load order
     * @param overrides topics the server's owner wrote, replacing shipped ones of the same id
     * @param hidden    ids the owner does not want offered at all
     * @param features  installed features; one without a topic gets a generated one
     * @param problems  what reading the files found, carried into {@link #problems()}
     */
    public static HelpLibrary of(List<HelpTopic> shipped, List<HelpTopic> overrides, Set<String> hidden,
                                 List<Feature> features, List<String> problems) {
        List<String> found = new ArrayList<>(problems == null ? List.of() : problems);
        Map<String, HelpTopic> byId = new LinkedHashMap<>();
        for (HelpTopic topic : shipped) {
            HelpTopic before = byId.putIfAbsent(topic.id(), topic);
            if (before != null) {
                found.add("Two plugins ship the topic '" + topic.id() + "': " + before.plugin() + " and "
                        + topic.plugin() + ". " + before.plugin() + "'s is used; override it in help.yml to choose.");
            }
        }
        for (HelpTopic topic : overrides) {
            byId.put(topic.id(), topic);
        }
        for (Feature feature : features == null ? List.<Feature>of() : features) {
            boolean covered = byId.values().stream().anyMatch(topic -> feature.id().equalsIgnoreCase(topic.module())
                    || topic.id().equalsIgnoreCase(feature.id()));
            if (!covered && !feature.commands().isEmpty()) {
                byId.put(feature.id().toLowerCase(Locale.ROOT), generated(feature));
            }
        }
        if (hidden != null) {
            hidden.forEach(id -> byId.remove(id.toLowerCase(Locale.ROOT)));
        }
        // Shared aliases ("ban" on the player page and the staff page) and links to topics of plugins that are not
        // installed are not faults: /help ban lists both, and a missing link is left out. HelpLint holds authors to
        // their own module; this only reports what a server owner has to act on.
        return new HelpLibrary(Map.copyOf(byId), List.copyOf(found));
    }

    /** A topic for a feature that shipped no guide: what it says about itself, and its commands. */
    static HelpTopic generated(Feature feature) {
        String id = feature.id().toLowerCase(Locale.ROOT);
        List<HelpSection> sections = new ArrayList<>();
        String about = feature.description().isBlank()
                ? feature.name() + " has not told me much about itself, but here is what it offers."
                : feature.description();
        sections.add(new HelpSection("What is " + shortened(feature.name(), 26) + "?", "About", about, null, null));
        List<String> commands = new ArrayList<>();
        for (CommandNote note : feature.commands()) {
            String first = note.command().split(" ")[0];
            if (!commands.contains(first)) {
                commands.add(first);
            }
        }
        return new HelpTopic(id, feature.name(), feature.description().isBlank() ? feature.name()
                : shortened(feature.description(), 140), "", null, HelpCategories.OTHER, 900, List.of(), List.of(),
                id, null, null, null, commands, List.of(), sections, feature.name(), true);
    }

    // ------------------------------------------------------------------ reading

    /** Every topic, whoever may read it — for {@code /help check}. */
    public List<HelpTopic> all() {
        return sorted(topics.values());
    }

    /** What loading found wrong. Empty on a healthy server. */
    public List<String> problems() {
        return problems;
    }

    public Optional<HelpTopic> byId(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(topics.get(id.strip().toLowerCase(Locale.ROOT)));
    }

    /** Whether this reader may see the topic here: their permission, its condition, its module. */
    public static boolean visible(HelpTopic topic, HelpContext context) {
        return (topic.permission() == null || context.has(topic.permission()))
                && (topic.module() == null || context.hasModule(topic.module()))
                && (topic.when() == null || topic.when().test(context));
    }

    /**
     * Whether this reader gets this question. Not when its answer quotes a setting this server does not have — a
     * module's part that only runs on the network's bank, say: a missing answer reads better than a "?" in one.
     * {@code /help check} still names the setting.
     */
    public static boolean visible(HelpSection section, HelpContext context) {
        return (section.permission() == null || context.has(section.permission()))
                && (section.when() == null || section.when().test(context))
                && HelpText.settingsShown(section.text()).stream().allMatch(key -> context.setting(key).isPresent());
    }

    /** The topics this reader may see, by category and then order. */
    public List<HelpTopic> visibleTo(HelpContext context) {
        return all().stream().filter(topic -> visible(topic, context)).toList();
    }

    /** The questions of a topic this reader gets. */
    public static List<HelpSection> sectionsFor(HelpTopic topic, HelpContext context) {
        return topic.sections().stream().filter(section -> visible(section, context)).toList();
    }

    /** The categories this reader has topics in, in shelf order, each with its topics. */
    public Map<String, List<HelpTopic>> byCategory(HelpContext context) {
        Map<String, List<HelpTopic>> shelves = new LinkedHashMap<>();
        for (HelpTopic topic : visibleTo(context)) {
            shelves.computeIfAbsent(topic.category(), category -> new ArrayList<>()).add(topic);
        }
        return shelves;
    }

    /**
     * What somebody typing {@code /help <words>} means, if it is one topic: its id, an alias, a command it covers
     * ({@code /help sell}), or its title. Only topics this reader may see.
     */
    public Optional<HelpTopic> find(String typed, HelpContext context) {
        String wanted = normal(typed);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        List<HelpTopic> mine = visibleTo(context);
        for (HelpTopic topic : mine) {
            if (topic.id().equals(wanted)) {
                return Optional.of(topic);
            }
        }
        // An alias several topics share is not one topic: the caller searches instead, and the reader picks.
        List<HelpTopic> aliased = mine.stream().filter(topic -> topic.aliases().contains(wanted)).toList();
        if (aliased.size() == 1) {
            return Optional.of(aliased.getFirst());
        }
        if (aliased.size() > 1) {
            return Optional.empty();
        }
        String command = wanted.startsWith("/") ? wanted.substring(1) : wanted;
        for (HelpTopic topic : mine) {
            if (topic.commands().contains(command) || topic.commands().contains(command.split(" ")[0])
                    && !topic.generated()) {
                return Optional.of(topic);
            }
        }
        for (HelpTopic topic : mine) {
            if (topic.commands().contains(command.split(" ")[0])) {
                return Optional.of(topic);
            }
        }
        for (HelpTopic topic : mine) {
            if (normal(topic.title()).equals(wanted)) {
                return Optional.of(topic);
            }
        }
        return Optional.empty();
    }

    /** A question of a topic by its number (1-based) or by words of its title or question. */
    public static Optional<HelpSection> section(HelpTopic topic, String typed, HelpContext context) {
        List<HelpSection> mine = sectionsFor(topic, context);
        String wanted = normal(typed);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        try {
            int number = Integer.parseInt(wanted);
            return number >= 1 && number <= mine.size() ? Optional.of(mine.get(number - 1)) : Optional.empty();
        } catch (NumberFormatException notANumber) {
            // Words, then.
        }
        for (HelpSection section : mine) {
            if (normal(section.title()).equals(wanted) || normal(section.question()).equals(wanted)) {
                return Optional.of(section);
            }
        }
        return mine.stream().filter(section -> WordSearch.matches(wanted, section.title(), section.question()))
                .findFirst();
    }

    /** Names close to something typed that matched nothing — ids, aliases, commands — for "did you mean". */
    public List<String> closest(String typed, HelpContext context) {
        Set<String> names = new LinkedHashSet<>();
        for (HelpTopic topic : visibleTo(context)) {
            names.add(topic.id());
            names.addAll(topic.aliases());
            names.addAll(topic.commands());
        }
        return Closest.to(normal(typed), names, 3);
    }

    /**
     * Topics, and the question in each that answers best, for some words. Every word must appear somewhere in the
     * topic (its titles, aliases, commands or the answers as this reader would read them); the score then prefers
     * titles over aliases over commands over questions over answers.
     */
    public List<Hit> search(String typed, HelpContext context) {
        String wanted = normal(typed);
        if (wanted.isEmpty()) {
            return List.of();
        }
        String[] words = wanted.split(" ");
        List<Hit> hits = new ArrayList<>();
        for (HelpTopic topic : visibleTo(context)) {
            List<HelpSection> sections = sectionsFor(topic, context);
            List<String> answers = new ArrayList<>();
            for (HelpSection section : sections) {
                answers.add(answer(section, context));
            }
            String[] everything = everything(topic, sections, answers);
            if (!WordSearch.matches(wanted, everything)) {
                continue;
            }
            int score = 0;
            for (String word : words) {
                score += weight(word, topic.title(), 12) + weight(word, topic.summary(), 3)
                        + weight(word, String.join(" ", topic.aliases()), 9)
                        + weight(word, String.join(" ", topic.keywords()), 7)
                        + weight(word, String.join(" ", topic.commands()), 6);
            }
            HelpSection best = null;
            int bestScore = 0;
            for (int index = 0; index < sections.size(); index++) {
                HelpSection section = sections.get(index);
                int sectionScore = 0;
                for (String word : words) {
                    sectionScore += weight(word, section.question() + " " + section.title(), 5)
                            + weight(word, answers.get(index), 1);
                }
                if (sectionScore > bestScore) {
                    bestScore = sectionScore;
                    best = section;
                }
            }
            hits.add(new Hit(topic, best, score + bestScore));
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed().thenComparing(hit -> hit.topic().title()));
        return List.copyOf(hits);
    }

    /** A section's answer as the reader would read it, without markup — what search and the console see. */
    static String answer(HelpSection section, HelpContext context) {
        return HelpText.plain(HelpText.render(section.text(), context, new ArrayList<>()));
    }

    private static String[] everything(HelpTopic topic, List<HelpSection> sections, List<String> answers) {
        List<String> all = new ArrayList<>(List.of(topic.id(), topic.title(), topic.summary(), topic.intro()));
        all.addAll(topic.aliases());
        all.addAll(topic.keywords());
        all.addAll(topic.commands());
        for (HelpSection section : sections) {
            all.add(section.question());
            all.add(section.title());
        }
        all.addAll(answers);
        return all.toArray(String[]::new);
    }

    private static int weight(String word, String text, int weight) {
        return WordSearch.normal(text).contains(word) ? weight : 0;
    }

    private static List<HelpTopic> sorted(java.util.Collection<HelpTopic> topics) {
        return topics.stream()
                .sorted(Comparator.comparingInt((HelpTopic topic) -> HelpCategories.rank(topic.category()))
                        .thenComparing(HelpTopic::category)
                        .thenComparingInt(HelpTopic::order)
                        .thenComparing(HelpTopic::title, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static String normal(String text) {
        return WordSearch.normal(text);
    }

    private static String shortened(String text, int most) {
        String clean = text.strip();
        return clean.length() <= most ? clean : clean.substring(0, most - 1).strip() + "…";
    }
}
