package de.raindancer.core.platform.network;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.NetworkEconomy;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * {@code /network} — the servers of the network and who is where; {@code /network bank} — which server keeps the
 * network's money; {@code /network bank here confirm} — makes this server the bank. The bank never moves by itself: its
 * balances are its own server's, so moving it is the owner's call.
 */
public final class NetworkCommand implements BasicCommand {

    public static final String PERMISSION = "rainscore.network";
    private static final MiniMessage MINI = MiniMessage.miniMessage();

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
        CommandSender sender = source.getSender();
        Plugin core = Bukkit.getPluginManager().getPlugin("RainsCore");
        if (core == null || !RainsCore.isAvailable() || !RainsCore.get().network().isNetwork()) {
            say(sender, "<gray>This server is not part of a network (plugins/RainsCore/network.yml).");
            return;
        }
        RainsCore rains = RainsCore.get();
        String self = rains.network().serverId();
        String word = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        Scheduling.async(core, () -> {
            if (word.equals("bank")) {
                rains.networkRoles().ifPresentOrElse(roles -> {
                    if (args.length > 1 && args[1].equalsIgnoreCase("here")) {
                        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                            say(sender, "<yellow>The balances are the bank server's own economy database; they do not "
                                    + "move with it. Make <white><id></white> the bank only if it already holds the right "
                                    + "balances. <gray>/network bank here confirm", self);
                            return;
                        }
                        if (roles.moveHome(NetworkEconomy.ROLE, self)) {
                            say(sender, "<green>This server (<white><id></white>) is the network's bank now. "
                                    + "<gray>It takes over within a few seconds if it runs an economy.", self);
                        } else {
                            say(sender, "<red>The database could not be written; the bank did not move.");
                        }
                        return;
                    }
                    String home = roles.home(NetworkEconomy.ROLE).orElse("none yet");
                    String holder = roles.holder(NetworkEconomy.ROLE).orElse("nobody — money cannot move");
                    say(sender, "<gold>Bank</gold> <gray>belongs to <white><id></white>, answering now: <white>"
                            + MINI.escapeTags(holder) + "</white>. <dark_gray>/network bank here</dark_gray> moves it.", home);
                }, () -> say(sender, "<red>The network's services are not running."));
                return;
            }
            rains.networkPlayers().ifPresentOrElse(players -> {
                var byServer = players.byServer(sender.hasPermission("rainscore.vanish.see"));
                for (NetworkServers.Server server : players.servers()) {
                    int count = byServer.getOrDefault(server.id(), List.of()).size();
                    say(sender, "<gold><id></gold> <gray>" + count + " online" + (server.id().equals(self) ? " · this server" : ""),
                            server.id());
                }
            }, () -> say(sender, "<red>The network's services are not running."));
        });
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return List.of("bank");
        }
        if (args.length == 2 && "bank".equalsIgnoreCase(args[0])) {
            return List.of("here");
        }
        if (args.length == 3 && "bank".equalsIgnoreCase(args[0]) && "here".equalsIgnoreCase(args[1])) {
            return List.of("confirm");
        }
        return List.of();
    }

    private static void say(CommandSender sender, String miniMessage) {
        sender.sendMessage(MINI.deserialize(miniMessage));
    }

    private static void say(CommandSender sender, String miniMessage, String id) {
        sender.sendMessage(MINI.deserialize(miniMessage, Placeholder.unparsed("id", id)));
    }
}
