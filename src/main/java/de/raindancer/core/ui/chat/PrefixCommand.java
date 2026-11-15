package de.raindancer.core.ui.chat;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.RainsCore;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.ui.prompt.Parsed;
import de.raindancer.core.ui.text.NameStyle;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * {@code /prefix} — the menu with no arguments; everything in it also typeable, for the console and
 * for people who know what they want:
 *
 * <pre>
 * /prefix mode shared|per-plugin       /prefix tag &lt;text…&gt;        /prefix style &lt;#a,#b|bold|animated&gt;
 * /prefix format &lt;markup with {tag}&gt;   /prefix show|hide              /prefix reload | reset | preview
 * /prefix plugin &lt;name&gt; tag &lt;text…&gt; | style &lt;style&gt; | show | hide | reset
 * </pre>
 */
public final class PrefixCommand implements BasicCommand {

    public static final String PERMISSION = "rainscore.prefix";
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final List<String> WORDS =
            List.of("mode", "tag", "style", "format", "show", "hide", "plugin", "preview", "reload", "reset");

    private final Supplier<PrefixService> service;

    public PrefixCommand() {
        this(() -> RainsCore.get().prefixes());
    }

    PrefixCommand(Supplier<PrefixService> service) {
        this.service = service;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(PERMISSION);
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        run(source.getSender(), args);
    }

    void run(CommandSender sender, String[] args) {
        PrefixService prefixes = service.get();
        if (args.length == 0) {
            if (sender instanceof Player player) {
                new PrefixMenu(player, RainsCore.get().chatFor("Core").brand(), null, prefixes).open();
            } else {
                preview(sender);
            }
            return;
        }
        String rest = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "mode" -> {
                PrefixDesign.Mode mode = switch (rest.toLowerCase(Locale.ROOT).replace('_', '-')) {
                    case "shared", "one" -> PrefixDesign.Mode.SHARED;
                    case "per-plugin", "plugin", "each" -> PrefixDesign.Mode.PER_PLUGIN;
                    default -> null;
                };
                if (mode == null) {
                    say(sender, Messages.spoken("prefix-design.which-mode", "<red>Either <white>shared</white> or <white>per-plugin</white>."));
                    return;
                }
                apply(sender, "mode " + rest, d -> d.withMode(mode));
            }
            case "tag" -> {
                Parsed<String> tag = PrefixMenu.tag(rest);
                if (refused(sender, tag)) {
                    return;
                }
                apply(sender, "tag " + tag.value(), d -> d.withTag(tag.value()));
            }
            case "style" -> {
                NameStyle style = NameStyle.parse(rest);
                if (!rest.isBlank() && style.isEmpty()) {
                    say(sender, Messages.spoken("prefix-design.no-style", "<red>No colour or decoration in that. Like <white>#ff8800,#ffee00|bold</white>."));
                    return;
                }
                apply(sender, "style " + rest, d -> d.withStyle(style));
            }
            case "format", "shape" -> {
                Parsed<String> format = PrefixMenu.format(rest);
                if (refused(sender, format)) {
                    return;
                }
                apply(sender, "format", d -> d.withFormat(format.value()));
            }
            case "show", "on" -> apply(sender, "shown", d -> d.withShown(true));
            case "hide", "off" -> apply(sender, "hidden", d -> d.withShown(false));
            case "reset" -> apply(sender, "reset", d -> PrefixDesign.DEFAULT);
            case "reload" -> {
                prefixes.reload();
                say(sender, prefixes.isFileBroken()
                        ? Messages.spoken("prefix-design.file-broken", "<red>prefix.yml is broken; the defaults are used until it is fixed. The console says where.")
                        : MINI.deserialize("<green>prefix.yml read again."));
                preview(sender);
            }
            case "preview" -> preview(sender);
            case "plugin" -> plugin(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> say(sender, "<red>/prefix " + String.join("|", WORDS));
        }
    }

    private void plugin(CommandSender sender, String[] args) {
        if (args.length < 2) {
            say(sender, "<red>/prefix plugin <name> tag|style|show|hide|reset");
            return;
        }
        String plugin = args[0];
        String rest = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        UnaryOperator<PrefixDesign.PluginPrefix> change = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "tag" -> {
                Parsed<String> tag = PrefixMenu.tag(rest);
                yield tag.isOk() ? p -> new PrefixDesign.PluginPrefix(tag.value(), p.style(), p.shown()) : null;
            }
            case "style" -> {
                NameStyle style = NameStyle.parse(rest);
                yield p -> new PrefixDesign.PluginPrefix(p.tag(), style, p.shown());
            }
            case "show", "on" -> p -> new PrefixDesign.PluginPrefix(p.tag(), p.style(), true);
            case "hide", "off" -> p -> new PrefixDesign.PluginPrefix(p.tag(), p.style(), false);
            case "reset" -> p -> PrefixDesign.PluginPrefix.NONE;
            default -> null;
        };
        if (change == null) {
            say(sender, "<red>/prefix plugin <name> tag <text>|style <style>|show|hide|reset");
            return;
        }
        apply(sender, "plugin " + plugin + " " + args[1],
                d -> d.withPlugin(plugin, change.apply(d.pluginPrefix(plugin))));
    }

    private void apply(CommandSender sender, String what, UnaryOperator<PrefixDesign> change) {
        boolean saved = service.get().change(change);
        audit(sender, what);
        say(sender, saved ? MINI.deserialize("<green>Prefix changed.") : Messages.spoken("prefix-design.changed-not-saved",
                "<yellow>Prefix changed, but prefix.yml could not be written. This lasts until the next restart or "
                        + "reload."));
        preview(sender);
    }

    private static void audit(CommandSender sender, String what) {
        if (!RainsCore.isAvailable()) {
            return;
        }
        AuditEntry.Builder entry = AuditEntry.of("core", "changed the prefix").saying(what);
        if (sender instanceof Player player) {
            entry.by(player.getUniqueId(), player.getName());
        }
        RainsCore.get().audit().record(entry);
    }

    private static boolean refused(CommandSender sender, Parsed<?> parsed) {
        if (parsed.isOk()) {
            return false;
        }
        say(sender, "<red>" + parsed.problem());
        return true;
    }

    private static void preview(CommandSender sender) {
        List<String> plugins = Prefixes.known();
        for (String plugin : plugins.isEmpty() ? List.of("Claims") : plugins.subList(0, Math.min(3, plugins.size()))) {
            sender.sendMessage(MINI.deserialize(Prefixes.chatPrefix(plugin, plugin) + "<white>Hello from "
                    + de.raindancer.core.ui.text.Text.literal(plugin) + "!"));
        }
    }

    private static void say(CommandSender sender, String miniMessage) {
        sender.sendMessage(MINI.deserialize(miniMessage));
    }

    private static void say(CommandSender sender, net.kyori.adventure.text.Component said) {
        sender.sendMessage(said);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return WORDS.stream().filter(word -> word.startsWith(typed)).toList();
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        Stream<String> options = switch (first) {
            case "mode" -> args.length == 2 ? Stream.of("shared", "per-plugin") : Stream.empty();
            case "style" -> Stream.of("#ff8800,#ffee00|bold", "#5555ff,#55ffff|bold|animated", "gold|italic");
            case "format", "shape" -> PrefixMenu.SHAPES.stream().map(PrefixMenu.Shape::format);
            case "plugin" -> args.length == 2 ? Prefixes.known().stream()
                    : args.length == 3 ? Stream.of("tag", "style", "show", "hide", "reset") : Stream.empty();
            default -> Stream.empty();
        };
        return options.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }
}
