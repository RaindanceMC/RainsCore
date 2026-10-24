package de.raindancer.core.ui.help;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Contexts that are not a live server: a test's, and the one {@link HelpLint} reads a module's defaults through. */
public final class HelpContexts {

    private HelpContexts() {
    }

    /** Answers from these values; no permissions, plugins or modules until added. */
    public static Fixed fixed(Map<String, ?> settings) {
        Map<String, Object> copy = new HashMap<>(settings);
        return fixedWith(key -> lookup(copy, key));
    }

    /** Answers settings through {@code settings}; no permissions, plugins or modules until added. */
    public static Fixed fixedWith(Function<String, Optional<Object>> settings) {
        return new Fixed(settings, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), false, null);
    }

    private static Optional<Object> lookup(Map<String, Object> settings, String key) {
        if (key == null) {
            return Optional.empty();
        }
        Object exact = settings.get(key);
        if (exact != null) {
            return Optional.of(exact);
        }
        // A bare key finds a plugin:key entry, the way Core's registry resolves it.
        if (key.indexOf(':') < 0) {
            for (Map.Entry<String, Object> entry : settings.entrySet()) {
                if (entry.getKey().endsWith(":" + key)) {
                    return Optional.of(entry.getValue());
                }
            }
        }
        return Optional.empty();
    }

    /** A context made of fixed answers. Each {@code with…} returns a changed copy. */
    public static final class Fixed implements HelpContext {

        private final Function<String, Optional<Object>> settings;
        private final Set<String> permissions;
        private final Set<String> plugins;
        private final Set<String> modules;
        private final Map<String, String> facts;
        private final Map<String, String> topics;
        private final boolean everything;
        private final Function<String, Optional<String>> money;

        private Fixed(Function<String, Optional<Object>> settings, Set<String> permissions, Set<String> plugins, Set<String> modules,
                      Map<String, String> facts, Map<String, String> topics, boolean everything,
                      Function<String, Optional<String>> money) {
            this.settings = settings;
            this.permissions = Set.copyOf(permissions);
            this.plugins = lower(plugins);
            this.modules = lower(modules);
            this.facts = Map.copyOf(facts);
            this.topics = Map.copyOf(topics);
            this.everything = everything;
            this.money = money;
        }

        public Fixed withPermissions(Set<String> granted) {
            return new Fixed(settings, granted, plugins, modules, facts, topics, everything, money);
        }

        public Fixed withPlugins(Set<String> enabled) {
            return new Fixed(settings, permissions, enabled, modules, facts, topics, everything, money);
        }

        public Fixed withModules(Set<String> running) {
            return new Fixed(settings, permissions, plugins, running, facts, topics, everything, money);
        }

        public Fixed withFacts(Map<String, String> known) {
            return new Fixed(settings, permissions, plugins, modules, known, topics, everything, money);
        }

        public Fixed withTopics(Map<String, String> titlesById) {
            return new Fixed(settings, permissions, plugins, modules, facts, titlesById, everything, money);
        }

        public Fixed withMoney(Function<String, Optional<String>> formatter) {
            return new Fixed(settings, permissions, plugins, modules, facts, topics, everything, formatter);
        }

        /** Every permission held and every plugin and module present — what a lint renders under, to see every line. */
        public Fixed seeingEverything() {
            return new Fixed(settings, permissions, plugins, modules, facts, topics, true, money);
        }

        @Override
        public Optional<Object> setting(String key) {
            return key == null ? Optional.empty() : settings.apply(key);
        }

        @Override
        public boolean has(String permission) {
            return everything || permissions.contains(permission);
        }

        @Override
        public boolean hasPlugin(String name) {
            return everything || (name != null && plugins.contains(name.toLowerCase(Locale.ROOT)));
        }

        @Override
        public boolean hasModule(String id) {
            return everything || (id != null && modules.contains(id.toLowerCase(Locale.ROOT)));
        }

        @Override
        public Optional<String> fact(String name) {
            return Optional.ofNullable(facts.get(name));
        }

        @Override
        public Optional<String> money(String amount) {
            if (money != null) {
                return money.apply(amount);
            }
            return HelpValues.numeric(amount).map(value -> "⛃" + HelpValues.number(value));
        }

        @Override
        public Optional<String> topicTitle(String id) {
            return Optional.ofNullable(topics.get(id));
        }

        @Override
        public String serverName() {
            return "This server";
        }

        @Override
        public String readerName() {
            return "Reader";
        }

        private static Set<String> lower(Set<String> names) {
            return names.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        }
    }
}
