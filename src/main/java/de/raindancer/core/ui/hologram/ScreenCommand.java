package de.raindancer.core.ui.hologram;

import de.raindancer.core.RainsCore;
import de.raindancer.core.world.protection.LandAction;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * {@code /screen} — putting up, nudging and taking down screens. Staff ({@value #PERMISSION}) handle every screen
 * anywhere; players, while the server lets them, put up a few of their own where they may build — their base — and
 * handle only those.
 *
 * <pre>
 * /screen                      the kinds there are, click one to put it up
 * /screen kinds                the same as a list in chat
 * /screen place &lt;kind&gt;         a screen of that kind over your head
 * /screen up|down [blocks] [#] the nearest screen (or number #) a quarter block, or as far as told
 * /screen move &lt;#&gt;             screen # to over your head
 * /screen remove [#]           the nearest screen, or number #
 * /screen list                 the screens here (players: their own)
 * </pre>
 */
public final class ScreenCommand implements BasicCommand {

    public static final String PERMISSION = "rainscore.screens";
    public static final String DESCRIPTION = "Screens floating in the world: stats, boards, guilds — and everybody's own.";
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** Over the head of whoever places it. */
    static final double ABOVE = 2.2;

    private final ScreenRule rule = new ScreenRule();

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Screens screens = RainsCore.get().screens();
        String word = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean staff = sender.hasPermission(PERMISSION);
        if (word.equals("list")) {
            list(sender, screens, staff);
            return;
        }
        if (word.equals("kinds")) {
            kinds(sender, screens, staff);
            return;
        }
        if (!(sender instanceof Player player)) {
            say(sender, "<red>Only a player can say where a screen goes. /screen list works from here.");
            return;
        }
        if (!staff && !screens.forPlayersAtAll()) {
            say(player, "<red>Only staff put up screens on this server.");
            return;
        }
        Predicate<PlacedScreen> touchable = screen -> rule.mayTouch(screen, player.getUniqueId(), staff);
        switch (word) {
            case "place", "put" -> {
                if (args.length < 2) {
                    new ScreenMenu(player, screens, staff).open();
                    return;
                }
                Optional<ScreenKind> kind = screens.kind(args[1]);
                if (kind.isEmpty()) {
                    say(player, "<red>There is no screen called <white>" + MINI.escapeTags(args[1])
                            + "</white>. <gray>/screen shows them all.");
                    return;
                }
                place(player, screens, kind.get(), staff);
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
                meant(player, screens, number, touchable, staff).ifPresent(screen -> {
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
                meant(player, screens, number, touchable, staff).ifPresent(screen -> {
                    if (!staff && !mayBuildHere(player)) {
                        say(player, "<red>Only where you may build — your base.");
                        return;
                    }
                    screens.move(screen, overHead(player));
                    say(player, "<green>Screen <white>#" + screen.number() + "</white> is over your head now.");
                });
            }
            case "remove", "delete" -> meant(player, screens, number(args, 1), touchable, staff).ifPresent(screen -> {
                screens.remove(screen);
                say(player, "<green>Screen <white>#" + screen.number() + "</white> is gone.");
            });
            default -> new ScreenMenu(player, screens, staff).open();
        }
    }

    /** Puts a screen of {@code kind} up over the player's head, if they may. */
    static void place(Player player, Screens screens, ScreenKind kind, boolean staff) {
        ScreenRule.Refusal refusal = screens.refusal(player, kind.id(), staff);
        if (refusal != null) {
            say(player, switch (refusal) {
                case OFF -> "<red>Only staff put up screens on this server.";
                case KIND -> "<red>That screen is for staff to put up.";
                case LIMIT -> "<red>You have " + screens.limit() + " screen(s) up already — take one down first: "
                        + "<white>/screen remove";
            });
            return;
        }
        if (!staff && !mayBuildHere(player)) {
            say(player, "<red>Only where you may build — your base.");
            return;
        }
        PlacedScreen placed = screens.place(overHead(player), kind, staff ? null : player.getUniqueId());
        say(player, "<green>Screen <white>#" + placed.number() + "</white> — " + MINI.escapeTags(kind.title())
                + " — is up." + (kind.personal() ? " <gray>Everybody near it sees their own." : "")
                + " <gray>/screen up, /screen down to nudge it.");
    }

    private static boolean mayBuildHere(Player player) {
        var land = RainsCore.get().land();
        return land == null || land.can(player, player.getLocation(), LandAction.BUILD);
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

    private static Optional<PlacedScreen> meant(Player player, Screens screens, Integer number,
                                                Predicate<PlacedScreen> touchable, boolean staff) {
        Optional<PlacedScreen> found = screens.find(player.getLocation().add(0, ABOVE, 0), number, touchable);
        if (found.isEmpty()) {
            String whose = staff ? "screen" : "screen of yours";
            say(player, number == null
                    ? "<red>No " + whose + " within " + (int) Screens.REACH + " blocks. <gray>Stand near it, or give its number (/screen list)."
                    : "<red>There is no " + whose + " #" + number + ".");
        }
        return found;
    }

    private void list(CommandSender sender, Screens screens, boolean staff) {
        List<PlacedScreen> placed = screens.placed();
        if (!staff) {
            placed = sender instanceof Player player ? rule.mine(placed, player.getUniqueId()) : List.of();
        }
        if (placed.isEmpty()) {
            say(sender, staff ? "<gray>No screens up. /screen puts one up." : "<gray>You have no screens up. /screen puts one up.");
            return;
        }
        say(sender, staff ? "<gold><bold>Screens up here</bold></gold>" : "<gold><bold>Your screens</bold></gold> <gray>("
                + placed.size() + "/" + screens.limit() + ")");
        for (PlacedScreen screen : placed) {
            String title = screens.kind(screen.kind()).map(ScreenKind::title).orElse(screen.kind() + " <red>(not offered now)");
            String owner = !staff || screen.owner() == null ? ""
                    : " <dark_gray>— " + MINI.escapeTags(Optional.ofNullable(Bukkit.getOfflinePlayer(screen.owner()).getName())
                    .orElse(screen.owner().toString().substring(0, 8)));
            say(sender, String.format(Locale.ROOT, " <white>#%d</white> <yellow>%s</yellow> <gray>%s %.1f %.1f %.1f",
                    screen.number(), MINI.escapeTags(title), MINI.escapeTags(screen.world()), screen.x(), screen.y(),
                    screen.z()) + owner);
        }
    }

    private static void kinds(CommandSender sender, Screens screens, boolean staff) {
        List<ScreenKind> kinds = screens.kinds().stream().filter(kind -> staff || screens.forPlayers(kind.id())).toList();
        if (kinds.isEmpty()) {
            say(sender, "<gray>No screens to put up.");
            return;
        }
        say(sender, "<gold><bold>Screens</bold></gold> <gray>— click one to put it up over your head:");
        for (ScreenKind kind : kinds) {
            sender.sendMessage(MINI.deserialize(" <dark_gray>•</dark_gray> <yellow>" + MINI.escapeTags(kind.title())
                            + "</yellow>" + (kind.personal() ? " <aqua>(everybody their own)</aqua>" : "")
                            + " <dark_gray>" + kind.id())
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/screen place " + kind.id()))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(MINI.deserialize("<gray>"
                            + MINI.escapeTags(kind.description())))));
        }
    }

    static void say(CommandSender sender, String miniMessage) {
        sender.sendMessage(MINI.deserialize(miniMessage));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        boolean staff = sender.hasPermission(PERMISSION);
        Screens screens = RainsCore.get().screens();
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<PlacedScreen> touchable = staff ? screens.placed()
                : sender instanceof Player player ? rule.mine(screens.placed(), player.getUniqueId()) : List.of();
        Stream<String> options;
        if (args.length <= 1) {
            options = Stream.of("place", "up", "down", "move", "remove", "list", "kinds");
        } else if (args.length == 2 && List.of("place", "put").contains(args[0].toLowerCase(Locale.ROOT))) {
            options = screens.kinds().stream().map(ScreenKind::id).filter(id -> staff || screens.forPlayers(id));
        } else if (args.length == 2 && List.of("up", "down").contains(args[0].toLowerCase(Locale.ROOT))) {
            options = Stream.of("0.1", "0.25", "0.5", "1");
        } else if (List.of("move", "remove", "up", "down").contains(args[0].toLowerCase(Locale.ROOT))) {
            options = touchable.stream().map(screen -> "#" + screen.number());
        } else {
            options = Stream.empty();
        }
        return options.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }
}
