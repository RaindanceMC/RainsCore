package de.raindancer.core.data.settings;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.chat.ChatButton;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.text.Text;
import de.raindancer.core.platform.util.Closest;
import de.raindancer.core.ui.identity.Symbols;
import net.kyori.adventure.text.Component;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import de.raindancer.core.RainsCore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * {@code /settings} — the same settings as the menu, for somebody at a console or who prefers typing.
 *
 * <h2>Why both</h2>
 * A menu is better for finding something you cannot name; a command is better for changing something
 * you can, and it is the only one of the two that works from the console or a script. Neither is a
 * lesser copy of the other because both go through {@link SettingsRegistry}, so there is exactly one
 * place a setting is validated and written.
 */
public final class SettingsCommand implements BasicCommand {

    private static final String PERMISSION = "rainscore.settings";
    /** How many near names "did you mean" offers. */
    private static final int DID_YOU_MEAN = 3;
    /** Up to this many choices are shown as buttons; more are listed. */
    private static final int CLICKABLE_CHOICES = 12;

    /**
     * Nothing is held, because there is nothing to hold yet.
     *
     * <p>This is registered from the bootstrapper, which runs before {@code onEnable} — so the
     * registry, the chat and the navigation do not exist when it is constructed. It asks for them
     * when it is actually run. See {@code RainsCoreBootstrap} for why registration cannot wait.
     */
    public SettingsCommand() {
    }

    private SettingsNavigation navigation() {
        return RainsCore.get().settingsNavigation();
    }

    private Chat chat() {
        return RainsCore.get().chatFor("Core");
    }

    private Brand brand() {
        return chat().brand();
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(PERMISSION);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!RainsCore.isAvailable()) {
            return;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                SettingsMenu.root(player, brand(), chat(), navigation()).open();
            } else {
                help(sender);
            }
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help", "?" -> help(sender);
            case "list" -> list(sender, args);
            case "changed" -> changed(sender);
            case "get" -> get(sender, args);
            case "set" -> set(sender, args, null);
            case "sethere" -> set(sender, args, SettingsStore.Layer.SERVER);
            case "setgroup" -> set(sender, args, SettingsStore.Layer.GROUP);
            case "unsethere" -> unset(sender, args, SettingsStore.Layer.SERVER);
            case "unsetgroup" -> unset(sender, args, SettingsStore.Layer.GROUP);
            case "reset" -> reset(sender, args);
            case "search", "find" -> search(sender, args);
            default -> word(sender, args[0]);
        }
    }

    /** Words the command knows, for completion and for "did you mean". */
    private static final List<String> SUBCOMMANDS = List.of("help", "search", "list", "changed", "get", "set",
            "reset", "sethere", "setgroup", "unsethere", "unsetgroup");

    /** "/settings enchants" opens that plugin; "/settings fence-style" shows that setting; anything else is answered. */
    private void word(CommandSender sender, String typed) {
        SettingsNavigation.Resolved resolved = navigation().resolve(typed);
        switch (resolved.kind()) {
            case PLUGIN -> {
                if (sender instanceof Player player) {
                    new SettingsMenu(player, brand(), chat(), navigation(), resolved.target() + ":",
                            SettingsMenu.root(player, brand(), chat(), navigation())).open();
                } else {
                    listPlugin(sender, resolved.target());
                }
            }
            case SETTING -> get(sender, new String[]{"get", resolved.target()});
            case UNKNOWN -> {
                chat().no(sender, "<red>There is no command, plugin or setting called <white><word></white>.",
                        Chat.arg("word", typed));
                List<String> close = new ArrayList<>(Closest.to(typed, SUBCOMMANDS, 1));
                close.addAll(resolved.closest());
                offer(sender, close.stream().distinct().limit(4).toList());
                chat().row(sender, "<gray>  <white>/settings help</white> says what there is.");
            }
        }
    }

    /** "Did you mean" with each guess a button that runs it. */
    private void offer(CommandSender sender, List<String> guesses) {
        if (guesses.isEmpty()) {
            return;
        }
        if (!clickable(sender)) {
            chat().row(sender, "<gray>  Did you mean " + Text.literal(String.join(", ", guesses)) + "?");
            return;
        }
        List<ChatButton> options = new ArrayList<>();
        for (String guess : guesses) {
            options.add(buttons().label("<aqua>[" + Text.literal(guess) + "]")
                    .tooltip("<gray>/settings " + Text.literal(guess)).runs("/settings " + guess));
        }
        chat().raw(sender, Component.text("  Did you mean ").color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .append(buttons().row(options.toArray(ChatButton[]::new))));
    }

    /** "/settings get" without a name, and the like: what is missing, and how to find it. */
    private void needs(CommandSender sender, String usage) {
        chat().no(sender, "<red>That needs more: <white><usage></white>", Chat.arg("usage", usage));
        chat().row(sender, "<gray>  A setting's name is at the bottom of its tooltip in <white>/settings</white>, "
                + "or find it with <white>/settings search <words></white>.");
    }

    // ---------------------------------------------------------------------------- help

    /** One line of the help: the command, what clicking it puts in the chat box, and what it does. */
    public record HelpLine(String command, String suggest, String what) {
    }

    /** Short enough for one line of chat at its default width: the long story is in each line's tooltip. */
    static List<HelpLine> help(List<SettingsNavigation.Plugin> plugins, boolean layered) {
        List<HelpLine> lines = new ArrayList<>();
        lines.add(new HelpLine("/settings", "/settings", "open the menu"));
        lines.add(new HelpLine("/settings search <words>", "/settings search ", "find one"));
        lines.add(new HelpLine("/settings <plugin>", "/settings ", "one plugin's"));
        lines.add(new HelpLine("/settings get <name>", "/settings get ", "show one"));
        lines.add(new HelpLine("/settings set <name> <value>", "/settings set ", "change it"));
        lines.add(new HelpLine("/settings reset <name>", "/settings reset ", "default again"));
        lines.add(new HelpLine("/settings changed", "/settings changed", "what differs"));
        lines.add(new HelpLine("/settings list [plugin]", "/settings list ", "names, values"));
        if (layered) {
            lines.add(new HelpLine("/settings sethere <name> <value>", "/settings sethere ", "this server only"));
            lines.add(new HelpLine("/settings setgroup <name> <value>", "/settings setgroup ", "this group"));
            lines.add(new HelpLine("/settings unsethere <name>", "/settings unsethere ", "network's again"));
        }
        return lines;
    }

    /** What each help line says when hovered: the part that does not fit on the line. */
    private static String longer(HelpLine line) {
        return switch (line.suggest().trim()) {
            case "/settings" -> "A door for every plugin; a setting's name is under its tooltip.";
            case "/settings search" -> "By any word of its name or what it does, e.g. \"flight time\".";
            case "/settings get" -> "What it is now, what it does, its default and what it may be.";
            case "/settings set" -> "Tab completion shows what it may be.";
            case "/settings changed" -> "Every setting that is not what its plugin shipped with.";
            case "/settings list" -> "Without a plugin: the plugins. With one: its settings, by section.";
            default -> line.what();
        };
    }

    private void help(CommandSender sender) {
        chat().tell(sender, "<white>Settings</white> <gray>— what the plugins here can be told to do. "
                + "Each has a name, like <white>flight-seconds-per-level</white>.");
        boolean layered = navigation().registry().keys().stream().anyMatch(key -> navigation().registry().isLayered(key));
        for (HelpLine line : help(navigation().plugins(), layered)) {
            String text = "<white>" + Text.literal(line.command()) + " <dark_gray>· <gray>" + Text.literal(line.what());
            if (clickable(sender)) {
                chat().raw(sender, Component.text(" ").append(buttons().label(text)
                        .tooltip("<gray>" + Text.literal(longer(line)) + "<newline><dark_gray>Click to type it")
                        .suggests(line.suggest()).render()));
            } else {
                chat().row(sender, " " + text + " <dark_gray>— " + Text.literal(longer(line)));
            }
        }
        List<SettingsNavigation.Plugin> plugins = navigation().plugins();
        if (clickable(sender)) {
            List<ChatButton> doors = new ArrayList<>();
            for (SettingsNavigation.Plugin plugin : plugins) {
                doors.add(buttons().label("<aqua>" + plugin.id()).tooltip("<gray>" + Text.literal(plugin.name()))
                        .runs("/settings " + plugin.id()));
            }
            chat().raw(sender, Component.text(" Plugins: ").color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                    .append(buttons().row(doors.toArray(ChatButton[]::new))));
        } else {
            chat().row(sender, "<gray> Plugins: <white>" + Text.literal(pluginsLine(plugins)));
        }
    }

    static String pluginsLine(List<SettingsNavigation.Plugin> plugins) {
        return String.join(", ", plugins.stream().map(SettingsNavigation.Plugin::id).toList());
    }

    private void changed(CommandSender sender) {
        if (sender instanceof Player player) {
            new SettingsMenu(player, brand(), chat(), navigation(), SettingsNavigation.CHANGED,
                    SettingsMenu.root(player, brand(), chat(), navigation())).open();
            return;
        }
        List<Setting<?>> changed = navigation().changed();
        chat().tell(sender, "<gray><count> setting(s) differ from their defaults.", Chat.arg("count", changed.size()));
        for (Setting<?> setting : changed) {
            chat().row(sender, "<dark_gray>  <white><key></white> = <gray><value></gray>  (default <default>)",
                    Chat.arg("key", setting.key()), Chat.arg("value", navigation().now(setting)),
                    Chat.arg("default", SettingCodec.display(setting.defaultValue())));
        }
    }

    /** Without a plugin, the plugins; with one, its settings by section. */
    private void list(CommandSender sender, String[] args) {
        if (args.length > 1) {
            navigation().plugin(args[1]).ifPresentOrElse(plugin -> listPlugin(sender, plugin.id()),
                    () -> word(sender, args[1]));
            return;
        }
        List<SettingsNavigation.Plugin> plugins = navigation().plugins();
        chat().tell(sender, "<gray><count> plugins have settings. Pick one:", Chat.arg("count", plugins.size()));
        for (SettingsNavigation.Plugin plugin : plugins) {
            int count = plugin.tree().visibleRoots().stream().mapToInt(root -> root.allSettings().size()).sum();
            String line = "<white>" + Text.literal(plugin.name()) + " <dark_gray>(" + plugin.id() + ") — <gray>"
                    + count + " settings";
            if (clickable(sender)) {
                chat().raw(sender, Component.text("  ").append(buttons().label(line)
                        .tooltip("<gray>List them").runs("/settings list " + plugin.id()).render()));
            } else {
                chat().row(sender, "  " + line);
            }
        }
        var clashes = navigation().registry().clashes();
        if (!clashes.isEmpty()) {
            chat().warn(sender, "<count> setting name(s) are used by more than one plugin; "
                    + "say plugin:name to be sure which you mean.",
                    Chat.arg("count", clashes.size()));
        }
    }

    private void listPlugin(CommandSender sender, String id) {
        navigation().plugin(id).ifPresent(plugin -> {
            chat().tell(sender, "<white><name></white> <gray>— click a name to see it, or "
                    + "<white>/settings set <name> <value></white>.", Chat.arg("name", plugin.name()));
            for (SettingsTopic root : plugin.tree().visibleRoots()) {
                listSection(sender, root);
            }
        });
    }

    private void listSection(CommandSender sender, SettingsTopic topic) {
        if (!topic.settings().isEmpty()) {
            chat().row(sender, "<gold>  " + Text.literal(topic.title()));
        }
        for (Setting<?> setting : topic.settings()) {
            String line = "<white>" + setting.key() + " <dark_gray>= <gray>" + Text.literal(navigation().now(setting))
                    + (navigation().isChanged(setting) ? " <yellow>(changed)" : "");
            if (clickable(sender)) {
                chat().raw(sender, Component.text("    ").append(buttons().label(line)
                        .tooltip("<gray>" + Text.literal(setting.title())).runs("/settings get " + setting.key())
                        .render()));
            } else {
                chat().row(sender, "    " + line);
            }
        }
        topic.visibleChildren().forEach(child -> listSection(sender, child));
    }

    private void get(CommandSender sender, String[] args) {
        if (args.length < 2) {
            needs(sender, "/settings get <name>");
            return;
        }
        navigation().registry().setting(args[1]).ifPresentOrElse(setting -> {
            chat().tell(sender, "<white><title></white> <dark_gray>(<key>) <gray>in <where>",
                    Chat.arg("title", setting.title()), Chat.arg("key", setting.key()),
                    Chat.arg("where", String.join(" " + Symbols.ARROW + " ", navigation().trailOf(setting))));
            if (navigation().registry().isLayered(args[1])) {
                chat().row(sender, "<dark_gray>  set for <where>",
                        Chat.arg("where", where(navigation().registry().layerOf(args[1]))));
            }
            // The menu's lore without its own instructions: the buttons below say what to do here.
            for (String line : navigation().describe(setting)) {
                if (!line.isBlank() && !line.startsWith("<yellow>") && !line.startsWith("<dark_gray>")) {
                    chat().row(sender, "  " + line);
                }
            }
            String key = setting.key();
            if (clickable(sender)) {
                chat().raw(sender, Component.text("  ").append(buttons().row(
                        buttons().label("<yellow>[change]").tooltip("<gray>Type a new value; Tab shows what it may be")
                                .suggests("/settings set " + key + " "),
                        buttons().label("<gray>[reset]").tooltip("<gray>Back to the default")
                                .runs("/settings reset " + key),
                        buttons().label("<aqua>[open in the menu]").tooltip("<gray>Its page in /settings")
                                .runs("/settings " + navigation().pluginOf(setting)))));
            } else {
                chat().row(sender, "<dark_gray>  /settings set " + key + " <value>  ·  /settings reset " + key);
            }
        }, () -> unknown(sender, args[1]));
    }

    /** Gives a setting back to the next layer: the group's, else the network's. */
    private void unset(CommandSender sender, String[] args, SettingsStore.Layer layer) {
        if (args.length < 2) {
            needs(sender, "/settings " + args[0].toLowerCase(Locale.ROOT) + " <name>");
            return;
        }
        if (navigation().registry().setting(args[1]).isEmpty()) {
            unknown(sender, args[1]);
            return;
        }
        if (!navigation().registry().mayChangeAt(sender, args[1], layer)) {
            refusedReach(sender, layer);
            return;
        }
        navigation().registry().clearOverride(args[1], layer);
        SettingsSaving.saveThenTell(navigation().registry(), sender);
        chat().ok(sender, "<white><name></white> is <white><value></white> again, as <where> has it.",
                Chat.arg("name", args[1]), Chat.arg("value", navigation().registry().display(args[1])),
                Chat.arg("where", where(navigation().registry().layerOf(args[1]))));
    }

    private void refusedReach(CommandSender sender, SettingsStore.Layer layer) {
        chat().no(sender, "<red>Changing it for <where> needs <white><node></white>. <gray>/settings sethere changes it "
                        + "for this server only.", Chat.arg("where", where(layer)),
                Chat.arg("node", layer == SettingsStore.Layer.GROUP ? SettingsRegistry.GROUP_PERMISSION
                        : SettingsRegistry.NETWORK_PERMISSION));
    }

    private static String where(SettingsStore.Layer layer) {
        return switch (layer) {
            case SERVER -> "this server";
            case GROUP -> "this group of servers";
            case NETWORK -> "the whole network";
        };
    }

    private void set(CommandSender sender, String[] args, SettingsStore.Layer layer) {
        if (args.length < 3) {
            needs(sender, "/settings " + args[0].toLowerCase(Locale.ROOT) + " <name> <value>");
            return;
        }
        // Everything after the key, so a value with spaces in it works.
        String value = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (navigation().registry().setting(args[1]).isEmpty()) {
            unknown(sender, args[1]);
            return;
        }
        SettingsStore.Layer target = layer == null ? navigation().registry().layerOf(args[1]) : layer;
        if (!navigation().registry().mayChangeAt(sender, args[1], target)) {
            refusedReach(sender, target);
            return;
        }
        boolean changed = layer == null ? navigation().registry().set(args[1], value)
                : navigation().registry().set(args[1], value, layer);
        if (changed) {
            SettingsSaving.saveThenTell(navigation().registry(), sender);
            chat().ok(sender, words().raw("settings.changed"),
                    Chat.arg("name", args[1]),
                    Chat.arg("value", navigation().registry().display(args[1])));
            if (navigation().registry().isLayered(args[1])) {
                chat().row(sender, "<dark_gray>  for <where>",
                        Chat.arg("where", where(navigation().registry().layerOf(args[1]))));
            }
            return;
        }
        chat().no(sender, words().raw("settings.refused"),
                Chat.arg("value", value), Chat.arg("name", args[1]));
        navigation().registry().setting(args[1]).ifPresent(setting -> {
            if (setting.min() != null) {
                chat().row(sender, "<dark_gray>  it goes from " + setting.min()
                        + " to " + setting.max());
            } else if (!setting.choices().isEmpty() && setting.choices().size() <= CLICKABLE_CHOICES
                    && clickable(sender)) {
                // Few enough to click: each one sets it.
                List<ChatButton> choices = new ArrayList<>();
                for (String choice : setting.choices()) {
                    choices.add(buttons().label("<aqua>[" + Text.literal(choice) + "]")
                            .tooltip("<gray>Set it to " + Text.literal(choice))
                            .runs("/settings set " + args[1] + " " + choice));
                }
                chat().raw(sender, Component.text("  ").append(buttons().row(choices.toArray(ChatButton[]::new))));
            } else if (!setting.choices().isEmpty()) {
                chat().row(sender, "<dark_gray>  one of: " + Text.literal(String.join(", ", setting.choices())));
            }
        });
    }

    private void reset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            needs(sender, "/settings reset <name>");
            return;
        }
        if (navigation().registry().setting(args[1]).isEmpty()) {
            unknown(sender, args[1]);
            return;
        }
        if (!navigation().registry().mayChange(sender, args[1])) {
            refusedReach(sender, navigation().registry().layerOf(args[1]));
            return;
        }
        navigation().registry().reset(args[1]);
        SettingsSaving.saveThenTell(navigation().registry(), sender);
        chat().ok(sender, words().raw("settings.reset"),
                Chat.arg("name", args[1]),
                Chat.arg("value", navigation().registry().display(args[1])));
    }

    private void search(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (sender instanceof Player player) {
                SettingsSearchMenu.ask(player, brand(), chat(), navigation(), null);
            } else {
                needs(sender, "/settings search <words>");
            }
            return;
        }
        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (sender instanceof Player player) {
            new SettingsSearchMenu(player, brand(), chat(), navigation(), query,
                    SettingsMenu.root(player, brand(), chat(), navigation())).open();
            return;
        }
        List<Setting<?>> found = navigation().search(query);
        if (found.isEmpty()) {
            sender.sendMessage(words().prefixed("settings.search-none", "query", query));
            return;
        }
        sender.sendMessage(words().prefixed("settings.search-found", "count", found.size(), "query", query));
        for (Setting<?> setting : found) {
            chat().row(sender, "<dark_gray>  <white><key> <dark_gray>— <gray><title> <dark_gray>= <gray><value>",
                    Chat.arg("key", setting.key()), Chat.arg("title", setting.title()),
                    Chat.arg("value", navigation().registry().display(setting.key())));
        }
    }

    /** Nothing is called that: say what is close, clickable, and how to look for it instead. */
    private void unknown(CommandSender sender, String key) {
        sender.sendMessage(words().prefixed("settings.unknown", "name", key));
        List<String> close = navigation().closest(key, DID_YOU_MEAN);
        if (!close.isEmpty()) {
            Component line = words().get("settings.did-you-mean");
            if (clickable(sender)) {
                List<ChatButton> options = new ArrayList<>();
                for (String option : close) {
                    options.add(buttons().label("<aqua>[" + Text.literal(option) + "]")
                            .tooltip("<gray>Show " + Text.literal(option))
                            .runs("/settings get " + option));
                }
                line = line.append(buttons().row(options.toArray(ChatButton[]::new)));
            } else {
                line = line.append(Component.text(String.join(", ", close)));
            }
            chat().raw(sender, line);
        }
        sender.sendMessage(words().get("settings.look-for-it"));
    }

    private static boolean clickable(CommandSender sender) {
        return sender instanceof Player && RainsCore.isAvailable() && RainsCore.get().buttons() != null;
    }

    private static ChatButtons buttons() {
        return RainsCore.get().buttons();
    }

    private static Messages words() {
        return RainsCore.get().messages();
    }

    /**
     * Completions.
     *
     * <p>The third argument completes to what the setting can actually be — the choices of a choice,
     * true and false for a flag, the bounds of a number. Completing a value is the difference
     * between a command somebody uses and one they look up first.
     */
    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!RainsCore.isAvailable()) {
            return List.of();
        }
        if (args.length <= 1) {
            List<String> words = new ArrayList<>(SUBCOMMANDS);
            navigation().plugins().forEach(plugin -> words.add(plugin.id()));
            return words.stream()
                    .filter(word -> args.length == 0 || word.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
            return navigation().plugins().stream().map(SettingsNavigation.Plugin::id)
                    .filter(id -> id.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("search") && !args[0].equalsIgnoreCase("find")) {
            return keysMatching(navigation().registry().keys(), args[1]);
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("sethere")
                || args[0].equalsIgnoreCase("setgroup"))) {
            return navigation().registry().setting(args[1])
                    .map(SettingsCommand::valuesFor)
                    .orElse(List.of());
        }
        return List.of();
    }

    /**
     * Keys starting with what was typed first, then keys containing it — so "style" finds
     * fence-style even though nobody remembers it starts with "fence".
     */
    static List<String> keysMatching(List<String> keys, String typedSoFar) {
        String typed = typedSoFar == null ? "" : typedSoFar.toLowerCase(Locale.ROOT);
        List<String> starting = new ArrayList<>();
        List<String> containing = new ArrayList<>();
        for (String key : keys) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.startsWith(typed)) {
                starting.add(key);
            } else if (!typed.isEmpty() && lower.contains(typed)) {
                containing.add(key);
            }
        }
        starting.addAll(containing);
        return starting.stream().limit(50).toList();
    }

    private static List<String> valuesFor(Setting<?> setting) {
        if (!setting.choices().isEmpty()) {
            return setting.choices();
        }
        if (setting.type() == Boolean.class) {
            return List.of("true", "false");
        }
        if (setting.min() != null) {
            List<String> bounds = new ArrayList<>();
            bounds.add(String.valueOf(setting.min()));
            bounds.add(String.valueOf(setting.max()));
            return bounds;
        }
        return List.of();
    }
}
