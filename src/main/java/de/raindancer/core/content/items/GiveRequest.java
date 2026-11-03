package de.raindancer.core.content.items;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * What a {@code /give} asks for: one of the plugins' items, handed out here, or anything else, passed on to vanilla's
 * {@code minecraft:give} exactly as typed — selectors, components and all.
 */
public sealed interface GiveRequest {

    /** The most of one of ours a single give hands out: a hundred stacks. */
    int MOST = 6400;

    record Ours(String target, String key, int amount) implements GiveRequest {
    }

    record Vanilla(String command) implements GiveRequest {
    }

    record Refused(String why) implements GiveRequest {
    }

    /** @param ours whether a key ({@code fishing:rod-void}, lower case) is one of the plugins' items */
    static GiveRequest parse(String[] args, Predicate<String> ours) {
        String key = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (args.length < 2 || !key.contains(":") || key.startsWith("minecraft:") || !ours.test(key)) {
            return new Vanilla(args.length == 0 ? "minecraft:give" : "minecraft:give " + String.join(" ", args));
        }
        if (args.length > 3) {
            return new Refused("Too much: /give <player> " + key + " [amount]");
        }
        int amount = 1;
        if (args.length == 3) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException notANumber) {
                return new Refused("Not an amount: " + args[2]);
            }
            if (amount < 1 || amount > MOST) {
                return new Refused("The amount must be 1 to " + MOST + ".");
            }
        }
        return new Ours(args[0], key, amount);
    }

    /**
     * Items to suggest for what is typed so far: the plugins' keys, then vanilla's, each bare ({@code diamond}) and in
     * full ({@code minecraft:diamond}).
     */
    static List<String> suggest(String typed, Collection<String> ours, Collection<String> vanilla) {
        String start = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (String key : ours) {
            if (key.startsWith(start)) {
                found.add(key);
            }
        }
        for (String key : vanilla) {
            String bare = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
            if (bare.startsWith(start)) {
                found.add(bare);
            }
            if (!bare.equals(key) && key.startsWith(start)) {
                found.add(key);
            }
        }
        return found;
    }
}
