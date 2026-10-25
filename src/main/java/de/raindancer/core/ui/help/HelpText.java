package de.raindancer.core.ui.help;

import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A help text, filled in for one server and one reader.
 *
 * <p>The text is MiniMessage written by whoever wrote the guide, plus placeholders in braces:
 *
 * <pre>
 * {setting:economy:pay.tax-percent|percent}   a setting, live: "5 %"
 * {money:250}  {setting:KEY|money}             an amount in this server's currency: "⛃250"
 * {fact:economy:richest}                       something a plugin knows at runtime (HelpFacts)
 * {player}  {server}                           who reads, and where
 * {cmd:/pay &lt;player&gt; &lt;amount&gt;}               a command, clickable: puts "/pay " in the chat bar
 * {run:/spawn}                                 a command a click runs — only for harmless ones
 * {topic:shop}                                 a link to another topic, by its title
 * {url:https://wiki…|the wiki}                 a link out
 * {if:CONDITION}…{else}…{/if}                  one of two texts; CONDITION as in a section's when:
 * {{  }}                                       a literal brace
 * </pre>
 *
 * Formats after {@code |}: {@code percent}, {@code money}, {@code seconds}, {@code minutes}, {@code hours},
 * {@code days}, {@code ticks} (as a duration), {@code yesno}, {@code onoff}, {@code count} (of a list),
 * {@code number}, {@code name} (a key or constant as words: {@code minecraft:end/kill_dragon} → "kill dragon",
 * {@code TRAPPED_CHEST} → "trapped chest"), {@code lower}, {@code upper}.
 *
 * <p>Every value is inserted as the characters it is: a claim called {@code <red>x} reads as that, and cannot
 * colour or click anything. Only the author's own markup is markup.
 */
public final class HelpText {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** The colour every clickable thing in a help text has, so a reader learns it once. */
    static final String LINK = "#7DD3FC";

    private HelpText() {
    }

    private sealed interface Token permits Literal, Tag {
    }

    /** Text as written — already MiniMessage. */
    private record Literal(String text) implements Token {
    }

    /** What was between braces. */
    private record Tag(String content) implements Token {
    }

    /**
     * The text, filled in and parsed. Anything that could not be filled in is added to {@code problems} — which a
     * live page ignores and {@code /help check} and the lint report — and shown as "?".
     */
    public static Component render(String text, HelpContext context, List<String> problems) {
        String mini = expand(text, context, problems);
        try {
            return MINI.deserialize(mini);
        } catch (RuntimeException broken) {
            problems.add("markup that cannot be read: " + broken.getMessage());
            return Component.text(MINI.stripTags(mini));
        }
    }

    /** The text filled in, still as MiniMessage. */
    public static String expand(String text, HelpContext context, List<String> problems) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        List<Token> tokens = tokens(text, problems);
        StringBuilder out = new StringBuilder();
        // Inside {if}: whether its first branch was chosen, and which branch we are in. Null when outside.
        Boolean chosen = null;
        boolean inElse = false;
        for (Token token : tokens) {
            if (token instanceof Tag tag) {
                String content = tag.content().strip();
                String lower = content.toLowerCase(Locale.ROOT);
                if (lower.startsWith("if:")) {
                    if (chosen != null) {
                        problems.add("an {if} inside another {if} — write two sentences instead: " + content);
                        continue;
                    }
                    chosen = condition(content.substring(3), context, problems);
                    inElse = false;
                    continue;
                }
                if (lower.equals("else")) {
                    if (chosen == null) {
                        problems.add("an {else} without an {if}");
                    } else {
                        inElse = true;
                    }
                    continue;
                }
                if (lower.equals("/if")) {
                    if (chosen == null) {
                        problems.add("an {/if} without an {if}");
                    }
                    chosen = null;
                    inElse = false;
                    continue;
                }
            }
            boolean active = chosen == null || chosen != inElse;
            if (!active) {
                continue;
            }
            switch (token) {
                case Literal literal -> out.append(literal.text());
                case Tag tag -> out.append(placeholder(tag.content().strip(), context, problems));
            }
        }
        if (chosen != null) {
            problems.add("an {if} that is never closed with {/if}");
        }
        return out.toString();
    }

    /** The setting keys a text reads — in placeholders and in {if:…} — for a lint to check. */
    public static Set<String> settingsRead(String text) {
        Set<String> keys = new LinkedHashSet<>();
        if (text == null) {
            return keys;
        }
        for (Token token : tokens(text, new ArrayList<>())) {
            if (!(token instanceof Tag tag)) {
                continue;
            }
            String content = tag.content().strip();
            String lower = content.toLowerCase(Locale.ROOT);
            if (lower.startsWith("setting:")) {
                keys.add(argument(content.substring("setting:".length())));
            } else if (lower.startsWith("if:")) {
                try {
                    keys.addAll(HelpExpression.parse(content.substring(3)).settings());
                } catch (IllegalArgumentException ignored) {
                    // Reported by expand, which the lint runs too.
                }
            } else if (lower.startsWith("money:")) {
                String amount = argument(content.substring("money:".length()));
                if (amount.contains(":")) {
                    keys.add(amount);
                }
            }
        }
        return keys;
    }

    /**
     * The setting keys whose values a text prints — {@code {setting:…}} and {@code {money:…}}, not conditions. A
     * condition on a setting this server lacks is simply off; a value it lacks would print as "?".
     */
    public static Set<String> settingsShown(String text) {
        Set<String> keys = new LinkedHashSet<>();
        if (text == null) {
            return keys;
        }
        for (Token token : tokens(text, new ArrayList<>())) {
            if (!(token instanceof Tag tag)) {
                continue;
            }
            String content = tag.content().strip();
            String lower = content.toLowerCase(Locale.ROOT);
            if (lower.startsWith("setting:")) {
                keys.add(argument(content.substring("setting:".length())));
            } else if (lower.startsWith("money:")) {
                String amount = argument(content.substring("money:".length()));
                if (amount.contains(":")) {
                    keys.add(amount);
                }
            }
        }
        return keys;
    }

    /**
     * The commands a text shows ({@code {cmd:…}}, {@code {run:…}}) in the branches this reader gets, in order, each
     * once — what the answer offers as buttons to run.
     */
    public static List<String> commandsMentioned(String text, HelpContext context) {
        List<String> found = new ArrayList<>();
        if (text == null) {
            return found;
        }
        Boolean chosen = null;
        boolean inElse = false;
        for (Token token : tokens(text, new ArrayList<>())) {
            if (!(token instanceof Tag tag)) {
                continue;
            }
            String content = tag.content().strip();
            String lower = content.toLowerCase(Locale.ROOT);
            if (lower.startsWith("if:")) {
                chosen = condition(content.substring(3), context, new ArrayList<>());
                inElse = false;
            } else if (lower.equals("else")) {
                inElse = true;
            } else if (lower.equals("/if")) {
                chosen = null;
            } else if ((chosen == null || chosen != inElse) && (lower.startsWith("cmd:") || lower.startsWith("run:"))) {
                String command = hub(content.substring(4), context).strip();
                command = command.startsWith("/") ? command : "/" + command;
                if (!found.contains(command)) {
                    found.add(command);
                }
            }
        }
        return found;
    }

    /** The topics a text links to, for a lint to check they exist. */
    public static Set<String> topicsLinked(String text) {
        Set<String> ids = new LinkedHashSet<>();
        if (text == null) {
            return ids;
        }
        for (Token token : tokens(text, new ArrayList<>())) {
            if (token instanceof Tag tag && tag.content().strip().toLowerCase(Locale.ROOT).startsWith("topic:")) {
                ids.add(tag.content().strip().substring("topic:".length()).strip().toLowerCase(Locale.ROOT));
            }
        }
        return ids;
    }

    /** What a component says, without its formatting. */
    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    // ------------------------------------------------------------------ placeholders

    private static String placeholder(String content, HelpContext context, List<String> problems) {
        int colon = content.indexOf(':');
        String kind = (colon < 0 ? content : content.substring(0, colon)).toLowerCase(Locale.ROOT);
        String rest = colon < 0 ? "" : content.substring(colon + 1);
        return switch (kind) {
            case "setting" -> setting(rest, context, problems);
            case "money" -> money(rest, context, problems);
            case "fact" -> fact(rest, context, problems);
            case "player" -> Text.literal(context.readerName());
            case "server" -> Text.literal(context.serverName());
            case "cmd" -> command(hub(rest, context), false, problems);
            case "run" -> command(hub(rest, context), true, problems);
            case "topic" -> topic(rest, context, problems);
            case "url" -> url(rest, problems);
            default -> {
                problems.add("{" + content + "} is no placeholder this help knows — see HelpText");
                yield Text.literal("{" + content + "}");
            }
        };
    }

    private static String setting(String spec, HelpContext context, List<String> problems) {
        String key = argument(spec);
        Optional<Object> value = context.setting(key);
        if (value.isEmpty()) {
            problems.add("the setting '" + key + "' is not declared here");
            return "?";
        }
        String format = format(spec);
        if (format.equals("money")) {
            String amount = context.display(key).orElse(HelpValues.display(value.get()));
            return Text.literal(context.money(amount).orElseGet(() -> {
                problems.add("'" + amount + "' (" + key + ") is no amount of money");
                return amount;
            }));
        }
        return Text.literal(formatted(value.get(), context.display(key).orElse(HelpValues.display(value.get())),
                format, problems));
    }

    private static String money(String spec, HelpContext context, List<String> problems) {
        String amount = argument(spec);
        if (amount.contains(":")) {
            Optional<String> shown = context.display(amount);
            if (shown.isEmpty()) {
                problems.add("the setting '" + amount + "' is not declared here");
                return "?";
            }
            amount = shown.get();
        }
        String finalAmount = amount;
        return Text.literal(context.money(amount).orElseGet(() -> {
            problems.add("'" + finalAmount + "' is no amount of money");
            return finalAmount;
        }));
    }

    private static String fact(String spec, HelpContext context, List<String> problems) {
        String name = argument(spec);
        Optional<String> known = context.fact(name);
        if (known.isEmpty()) {
            problems.add("nothing here knows the fact '" + name + "'");
            return "?";
        }
        return Text.literal(formatted(known.get(), known.get(), format(spec), problems));
    }

    static String formatted(Object value, String shown, String format, List<String> problems) {
        if (format.isEmpty()) {
            return shown;
        }
        Optional<Double> number = HelpValues.numeric(value);
        return switch (format) {
            case "percent" -> number.map(n -> HelpValues.number(n) + " %").orElse(shown);
            case "seconds" -> number.map(n -> HelpValues.duration(Math.round(n))).orElse(shown);
            case "minutes" -> number.map(n -> HelpValues.duration(Math.round(n * 60))).orElse(shown);
            case "hours" -> number.map(n -> HelpValues.duration(Math.round(n * 3_600))).orElse(shown);
            case "days" -> number.map(n -> HelpValues.duration(Math.round(n * 86_400))).orElse(shown);
            case "ticks" -> number.map(n -> HelpValues.duration(Math.round(n / 20))).orElse(shown);
            case "yesno" -> HelpValues.truthy(value) ? "yes" : "no";
            case "onoff" -> HelpValues.truthy(value) ? "on" : "off";
            case "count" -> value instanceof java.util.Collection<?> items ? String.valueOf(items.size())
                    : number.map(HelpValues::number).orElse(shown);
            case "number" -> number.map(HelpValues::number).orElse(shown);
            case "name" -> value instanceof java.util.Collection<?> items
                    ? String.join(", ", items.stream().map(item -> HelpValues.word(String.valueOf(item))).toList())
                    : HelpValues.word(shown);
            case "lower" -> shown.toLowerCase(Locale.ROOT);
            case "upper" -> shown.toUpperCase(Locale.ROOT);
            default -> {
                problems.add("'" + format + "' is no format (percent, money, seconds, minutes, hours, days, ticks, "
                        + "yesno, onoff, count, number, name, lower, upper)");
                yield shown;
            }
        };
    }

    /**
     * {@code /hub} as this server names the panel. Pages say /hub, but on a network the proxy takes /hub to send
     * people to the hub server, and there the panel is /smp.
     */
    private static String hub(String written, HelpContext context) {
        String command = written.strip();
        String bare = command.startsWith("/") ? command.substring(1) : command;
        int space = bare.indexOf(' ');
        String first = space < 0 ? bare : bare.substring(0, space);
        if (!first.equalsIgnoreCase("hub")) {
            return command;
        }
        String name = context.hubCommand();
        return name.equalsIgnoreCase("hub") ? command : "/" + name + (space < 0 ? "" : bare.substring(space));
    }

    private static String command(String written, boolean runs, List<String> problems) {
        String command = written.strip();
        if (command.isEmpty()) {
            problems.add("a {cmd:} with no command in it");
            return "";
        }
        if (!command.startsWith("/")) {
            command = "/" + command;
        }
        String action = runs ? "run_command" : "suggest_command";
        String target = runs ? command : typeable(command);
        String hover = runs ? "<gray>Click to run <white>" + Text.literal(command)
                : "<gray>Click to put it in the chat bar";
        return "<click:" + action + ":'" + quoted(target) + "'><hover:show_text:'" + quoted(hover) + "'><" + LINK + ">"
                + Text.literal(command) + "</" + LINK + "></hover></click>";
    }

    /** A usage line as something a reader can finish: the words before the first {@code <…>} or {@code […]}. */
    static String typeable(String usage) {
        StringBuilder typed = new StringBuilder();
        for (String word : usage.strip().split("\\s+")) {
            if (word.startsWith("<") || word.startsWith("[")) {
                break;
            }
            if (!typed.isEmpty()) {
                typed.append(' ');
            }
            typed.append(word);
        }
        return typed.append(' ').toString();
    }

    private static String topic(String id, HelpContext context, List<String> problems) {
        String wanted = id.strip().toLowerCase(Locale.ROOT);
        Optional<String> title = context.topicTitle(wanted);
        if (title.isEmpty()) {
            // Not necessarily wrong — the reader may not see that topic, or its plugin is not here. Said in
            // /help check, written as plain words on the page.
            problems.add("the topic '" + wanted + "' is not here, or not for this reader");
            return Text.literal(wanted);
        }
        return "<click:run_command:'/help " + quoted(wanted) + "'><hover:show_text:'<gray>Click to ask about "
                + quoted(Text.literal(title.get())) + "'><" + LINK + ">" + Text.literal(title.get()) + "</" + LINK
                + "></hover></click>";
    }

    private static String url(String spec, List<String> problems) {
        int bar = spec.indexOf('|');
        String address = (bar < 0 ? spec : spec.substring(0, bar)).strip();
        String label = bar < 0 ? address : spec.substring(bar + 1).strip();
        if (!(address.startsWith("https://") || address.startsWith("http://"))) {
            problems.add("'" + address + "' is not a web address");
            return Text.literal(label);
        }
        return "<click:open_url:'" + quoted(address) + "'><hover:show_text:'<gray>Opens " + quoted(Text.literal(address))
                + "'><" + LINK + "><u>" + Text.literal(label) + "</u></" + LINK + "></hover></click>";
    }

    private static boolean condition(String expression, HelpContext context, List<String> problems) {
        try {
            // A setting nobody here declares is off — that is what a condition on another server's part means.
            // A typo in one is caught by HelpLint in the module's build, which checks every key it reads.
            return HelpExpression.parse(expression).test(context);
        } catch (IllegalArgumentException broken) {
            problems.add("condition: " + broken.getMessage());
            return false;
        }
    }

    private static String argument(String spec) {
        int bar = spec.indexOf('|');
        return (bar < 0 ? spec : spec.substring(0, bar)).strip();
    }

    private static String format(String spec) {
        int bar = spec.indexOf('|');
        return bar < 0 ? "" : spec.substring(bar + 1).strip().toLowerCase(Locale.ROOT);
    }

    /** For inside a MiniMessage tag argument in single quotes. */
    private static String quoted(String text) {
        return text.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static List<Token> tokens(String text, List<String> problems) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '{' && index + 1 < text.length() && text.charAt(index + 1) == '{') {
                literal.append('{');
                index += 2;
                continue;
            }
            if (c == '}' && index + 1 < text.length() && text.charAt(index + 1) == '}') {
                literal.append('}');
                index += 2;
                continue;
            }
            if (c == '{') {
                int end = text.indexOf('}', index + 1);
                if (end < 0) {
                    problems.add("a '{' that is never closed — write {{ for a brace");
                    literal.append(text.substring(index));
                    break;
                }
                if (!literal.isEmpty()) {
                    tokens.add(new Literal(literal.toString()));
                    literal.setLength(0);
                }
                String content = text.substring(index + 1, end);
                if (content.indexOf('{') >= 0) {
                    problems.add("{" + content + "} — a placeholder cannot hold another one");
                }
                tokens.add(new Tag(content));
                index = end + 1;
                continue;
            }
            literal.append(c);
            index++;
        }
        if (!literal.isEmpty()) {
            tokens.add(new Literal(literal.toString()));
        }
        return tokens;
    }
}
