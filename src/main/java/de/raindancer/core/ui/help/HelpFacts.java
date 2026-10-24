package de.raindancer.core.ui.help;

import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.command.CommandSender;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * What a plugin knows at runtime and no setting holds, for {@code {fact:…}} in its guide: how many warps there
 * are, the price of the cheapest claim block, the next lottery draw.
 *
 * <pre>{@code
 * HelpFacts.provide("warp:count", reader -> String.valueOf(warps.all().size()));
 * }</pre>
 *
 * Named {@code plugin:name} like settings. A fact that throws or answers null reads as "?" and is a problem in
 * {@code /help check}; it never stops the page.
 */
public final class HelpFacts {

    private static final Map<String, Function<CommandSender, String>> FACTS = new ConcurrentHashMap<>();

    private HelpFacts() {
    }

    /** @param fact asked each time a page is read, for that reader; must be cheap and must not block */
    public static void provide(String name, Function<CommandSender, String> fact) {
        if (name != null && fact != null) {
            FACTS.put(name, fact);
        }
    }

    public static void withdraw(String name) {
        FACTS.remove(name);
    }

    public static Optional<String> ask(String name, CommandSender reader) {
        Function<CommandSender, String> fact = FACTS.get(name);
        if (fact == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(fact.apply(reader));
        } catch (RuntimeException broken) {
            return Optional.empty();
        }
    }

    /** Drops what a plugin that is going away provided. */
    public static int forgetFrom(ClassLoader loader) {
        int before = FACTS.size();
        FACTS.values().removeIf(fact -> PluginCode.isFrom(fact, loader));
        return before - FACTS.size();
    }
}
