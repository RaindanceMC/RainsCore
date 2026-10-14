package de.raindancer.core.data.settings;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.chat.ChatButton;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.text.Text;
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
                list(sender);
            }
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender);
            case "get" -> get(sender, args);
            case "set" -> set(sender, args, null);
            case "sethere" -> set(sender, args, SettingsStore.Layer.SERVER);
            case "setgroup" -> set(sender, args, SettingsStore.Layer.GROUP);
            case "unsethere" -> unset(sender, args, SettingsStore.Layer.SERVER);
            case "unsetgroup" -> unset(sender, args, SettingsStore.Layer.GROUP);
            case "reset" -> reset(sender, args);
            case "search", "find" -> search(sender, args);
            default -> {
                // "/settings fence-style" — a setting's name where a word was expected: show it.
                if (navigation().registry().setting(args[0]).isPresent()) {
                    get(sender, new String[]{"get", args[0]});
                } else {
                    usage(sender);
                }
            }
        }
    }

    private void list(CommandSender sender) {
        List<String> keys = navigation().registry().keys();
        chat().tell(sender, "<gray><count> settings:", Chat.arg("count", keys.size()));
        for (String key : keys) {
            chat().row(sender, "<dark_gray>  <white><key> <dark_gray>= <gray><value>",
                    Chat.arg("key", key), Chat.arg("value", navigation().registry().display(key)));
        }
        var clashes = navigation().registry().clashes();
        if (!clashes.isEmpty()) {
            chat().warn(sender, "<count> setting name(s) are used by more than one plugin; "
                    + "say plugin:name to be sure which you mean.",
                    Chat.arg("count", clashes.size()));
        }
    }

    private void get(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender);
            return;
        }
        navigation().registry().setting(args[1]).ifPresentOrElse(setting -> {
            chat().tell(sender, "<white><name></white> is <white><value></white>.",
                    Chat.arg("name", setting.title()),
                    Chat.arg("value", navigation().registry().display(args[1])));
            if (navigation().registry().isLayered(args[1])) {
                chat().row(sender, "<dark_gray>  set for <where>",
                        Chat.arg("where", where(navigation().registry().layerOf(args[1]))));
            }
            List<String> lines = navigation().describe(setting);
            // The last line is the menu's "click to change"; here the buttons below say it.
            for (String line : lines.subList(0, Math.max(0, lines.size() - 1))) {
                if (!line.isBlank()) {
                    chat().row(sender, "<dark_gray>  " + line);
                }
            }
            if (clickable(sender)) {
                String key = setting.key();
                chat().raw(sender, Component.text("  ").append(buttons().row(
                        buttons().label("<yellow>[change]").tooltip("<gray>Type a new value")
                                .suggests("/settings set " + key + " "),
                        buttons().label("<gray>[reset]").tooltip("<gray>Back to what it shipped with")
                                .runs("/settings reset " + key))));
            }
        }, () -> unknown(sender, args[1]));
    }

    /** Gives a setting back to the next layer: the group's, else the network's. */
    private void unset(CommandSender sender, String[] args, SettingsStore.Layer layer) {
        if (args.length < 2) {
            usage(sender);
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
            usage(sender);
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
            usage(sender);
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
                usage(sender);
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

    private void usage(CommandSender sender) {
        chat().tell(sender, "<gray>/settings <dark_gray>— the menu");
        if (!clickable(sender)) {
            chat().row(sender, "<dark_gray>  /settings list");
            chat().row(sender, "<dark_gray>  /settings search <word>");
            chat().row(sender, "<dark_gray>  /settings get <name>");
            chat().row(sender, "<dark_gray>  /settings set <name> <value>");
            chat().row(sender, "<dark_gray>  /settings reset <name>");
            chat().row(sender, "<dark_gray>  /settings sethere|setgroup <name> <value> — only this server / group");
            chat().row(sender, "<dark_gray>  /settings unsethere|unsetgroup <name> — back to the network's");
            return;
        }
        // Each line puts the command in the chat box, ready to finish.
        for (String[] line : new String[][]{
                {"/settings list", "/settings list", "Every setting and what it is now"},
                {"/settings search <word>", "/settings search ", "Find a setting by a word in it"},
                {"/settings get <name>", "/settings get ", "One setting, what it is and does"},
                {"/settings set <name> <value>", "/settings set ", "Change one"},
                {"/settings reset <name>", "/settings reset ", "Put one back to what it shipped with"}}) {
            chat().raw(sender, Component.text("  ").append(buttons().label("<gray>" + Text.literal(line[0]))
                    .tooltip("<gray>" + line[2] + "<newline><dark_gray>Click to type it").suggests(line[1]).render()));
        }
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
            return List.of("list", "search", "get", "set", "sethere", "setgroup", "unsethere", "unsetgroup", "reset").stream()
                    .filter(word -> args.length == 0 || word.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
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
