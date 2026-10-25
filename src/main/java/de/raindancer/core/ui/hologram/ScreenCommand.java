package de.raindancer.core.ui.hologram;

import de.raindancer.core.RainsCore;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@code /screen} — putting up, nudging and taking down screens ({@value #PERMISSION}).
 *
 * <pre>
 * /screen                      the kinds there are, click one to put it up
 * /screen place &lt;kind&gt;         a screen of that kind over your head
 * /screen up|down [blocks] [#] the nearest screen (or number #) a quarter block, or as far as told
 * /screen move &lt;#&gt;             screen # to over your head
 * /screen remove [#]           the nearest screen, or number #
 * /screen list                 every screen on this server
 * </pre>
 */
public final class ScreenCommand implements BasicCommand {

    public static final String PERMISSION = "rainscore.screens";
    public static final String DESCRIPTION = "Screens floating in the world: stats, boards, guilds — and everybody's own.";
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** Over the head of whoever places it. */
    private static final double ABOVE = 2.2;

    private final ScreenRule rule = new ScreenRule();

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Screens screens = RainsCore.get().screens();
        String word = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (word.equals("list")) {
            list(sender, screens);
            return;
        }
        if (!(sender instanceof Player player)) {
            say(sender, "<red>Only a player can say where a screen goes. /screen list works from here.");
            return;
        }
        switch (word) {
            case "place", "put" -> {
                if (args.length < 2) {
                    kinds(player, screens);
                    return;
                }
                Optional<ScreenKind> kind = screens.kind(args[1]);
                if (kind.isEmpty()) {
                    say(player, "<red>There is no screen called <white>" + MINI.escapeTags(args[1])
                            + "</white>. <gray>/screen shows them all.");
                    return;
                }
                PlacedScreen placed = screens.place(overHead(player), kind.get());
                say(player, "<green>Screen <white>#" + placed.number() + "</white> — " + MINI.escapeTags(kind.get().title())
                        + " — is up." + (kind.get().personal() ? " <gray>Everybody near it sees their own." : "")
                        + " <gray>/screen up, /screen down to nudge it.");
            }
            case "up", "down" -> {
                // "/screen up", "/screen up 0.5", "/screen up 0.5 3", "/screen up #3"
                boolean numberFirst = args.length > 1 && args[1].startsWith("#");
                Double blocks = rule.nudge(args.length > 1 && !numberFirst ? args[1] : null);
                if (blocks == null) {
                    say(player, "<red><white>" + MINI.escapeTags(args[1]) + "</white> is no number of blocks.");
                    return;
                }
                Integer number = number(args, numberFirst ? 1 : 2);
                meant(player, screens, number).ifPresent(screen -> {
                    PlacedScreen moved = screens.raise(screen, word.equals("up") ? blocks : -blocks);
                    say(player, "<green>Screen <white>#" + moved.number() + "</white> moved " + word + " "
                            + String.format(Locale.ROOT, "%.2f", blocks) + " block(s).");
                });
            }
            case "move", "here" -> {
                Integer number = number(args, 1);
                if (number == null) {
                    say(player, "<red>/screen move <number> <gray>— /screen list shows the numbers.");
                    return;
                }
                meant(player, screens, number).ifPresent(screen -> {
                    screens.move(screen, overHead(player));
                    say(player, "<green>Screen <white>#" + screen.number() + "</white> is over your head now.");
                });
            }
            case "remove", "delete" -> meant(player, screens, number(args, 1)).ifPresent(screen -> {
                screens.remove(screen);
                say(player, "<green>Screen <white>#" + screen.number() + "</white> is gone.");
            });
            default -> kinds(player, screens);
        }
    }

    private static Location overHead(Player player) {
        return player.getLocation().add(0, ABOVE, 0);
    }

    /** Word {@code index} as a screen number, written {@code 3} or {@code #3}; null when missing or no number. */
    private static Integer number(String[] args, int index) {
        if (index >= args.length) {
            return null;
        }
        String word = args[index].startsWith("#") ? args[index].substring(1) : args[index];
        try {
            return Integer.parseInt(word);
        } catch (NumberFormatException notOne) {
            return null;
        }
    }

    private static Optional<PlacedScreen> meant(Player player, Screens screens, Integer number) {
        Optional<PlacedScreen> found = screens.find(player.getLocation().add(0, ABOVE, 0), number);
        if (found.isEmpty()) {
            say(player, number == null
                    ? "<red>No screen within " + (int) Screens.REACH + " blocks. <gray>Stand near it, or give its number (/screen list)."
                    : "<red>There is no screen #" + number + ".");
        }
        return found;
    }

    private static void kinds(Player player, Screens screens) {
        List<ScreenKind> kinds = screens.kinds();
        if (kinds.isEmpty()) {
            say(player, "<gray>No plugin offers screens yet.");
            return;
        }
        say(player, "<gold><bold>Screens</bold></gold> <gray>— click one to put it up over your head:");
        for (ScreenKind kind : kinds) {
            Component line = MINI.deserialize(" <dark_gray>•</dark_gray> <yellow>" + MINI.escapeTags(kind.title())
                            + "</yellow>" + (kind.personal() ? " <aqua>(everybody their own)</aqua>" : "")
                            + " <dark_gray>" + kind.id())
                    .clickEvent(ClickEvent.runCommand("/screen place " + kind.id()))
                    .hoverEvent(HoverEvent.showText(MINI.deserialize("<gray>" + MINI.escapeTags(kind.description())
                            + "\n<dark_gray>from " + screens.ownerOf(kind.id()).orElse("?"))));
            player.sendMessage(line);
        }
        say(player, "<gray>/screen up|down [blocks] [#] · /screen move <#> · /screen remove [#] · /screen list");
    }

    private static void list(CommandSender sender, Screens screens) {
        List<PlacedScreen> placed = screens.placed();
        if (placed.isEmpty()) {
            say(sender, "<gray>No screens up. /screen puts one up.");
            return;
        }
        say(sender, "<gold><bold>Screens up here</bold></gold>");
        for (PlacedScreen screen : placed) {
            String title = screens.kind(screen.kind()).map(ScreenKind::title).orElse(screen.kind() + " <red>(not offered now)");
            say(sender, String.format(Locale.ROOT, " <white>#%d</white> <yellow>%s</yellow> <gray>%s %.1f %.1f %.1f",
                    screen.number(), title, MINI.escapeTags(screen.world()), screen.x(), screen.y(), screen.z()));
        }
    }

    private static void say(CommandSender sender, String miniMessage) {
        sender.sendMessage(MINI.deserialize(miniMessage));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        Stream<String> options;
        if (args.length <= 1) {
            options = Stream.of("place", "up", "down", "move", "remove", "list");
        } else if (args.length == 2 && List.of("place", "put").contains(args[0].toLowerCase(Locale.ROOT))) {
            options = RainsCore.get().screens().kinds().stream().map(ScreenKind::id);
        } else if (args.length == 2 && List.of("up", "down").contains(args[0].toLowerCase(Locale.ROOT))) {
            options = Stream.of("0.1", "0.25", "0.5", "1");
        } else if (List.of("move", "remove", "up", "down").contains(args[0].toLowerCase(Locale.ROOT))) {
            options = RainsCore.get().screens().placed().stream().map(screen -> "#" + screen.number());
        } else {
            options = Stream.empty();
        }
        return options.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }

    @Override
    public String permission() {
        return PERMISSION;
    }
}
