package de.raindancer.core.social.friends;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.network.NetworkServers;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.core.platform.proxy.ProxyTransfer;
import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /friend} — friends across the network: {@code add|accept|deny|remove <name>}, {@code list} (who is online and
 * where), {@code requests}, {@code join <name>} (to the server a friend is on). Every answer is a query, so off the
 * server thread.
 */
public final class FriendCommand implements BasicCommand {

    static final List<String> SUBCOMMANDS = List.of("add", "accept", "deny", "remove", "list", "requests", "join");

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("Only players have friends.");
            return;
        }
        Optional<Socials> socials = Socials.installed();
        Plugin core = Bukkit.getPluginManager().getPlugin("RainsCore");
        if (socials.isEmpty() || core == null) {
            say(player, "friends.off");
            return;
        }
        String what = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        String name = args.length > 1 ? args[1] : "";
        Scheduling.async(core, () -> run(socials.get(), player, what, name));
    }

    private void run(Socials socials, Player player, String what, String name) {
        UUID me = player.getUniqueId();
        Friends friends = socials.friends();
        if (what.equals("list")) {
            list(socials, player);
            return;
        }
        if (what.equals("requests")) {
            List<UUID> asked = friends.requestsTo(me);
            if (asked.isEmpty()) {
                say(player, "friends.requests-empty");
            }
            asked.forEach(asker -> say(player, "friends.requests-line", "name", socials.lastName(asker).orElse("?")));
            return;
        }
        if (!SUBCOMMANDS.contains(what) || name.isBlank()) {
            say(player, "friends.usage");
            return;
        }
        Optional<UUID> other = socials.idOf(name);
        if (other.isEmpty()) {
            say(player, "friends.unknown", "name", name);
            return;
        }
        String theirs = socials.lastName(other.get()).orElse(name);
        String mine = player.getName();
        switch (what) {
            case "add" -> {
                Friends.Outcome outcome = friends.ask(me, other.get());
                say(player, "friends." + key(outcome), "name", theirs);
                if (outcome == Friends.Outcome.SENT) {
                    socials.players().tell(other.get(), "friends.asked-you", "name", mine);
                } else if (outcome == Friends.Outcome.ACCEPTED) {
                    socials.players().tell(other.get(), "friends.now-friends", "name", mine);
                }
            }
            case "accept" -> {
                Friends.Outcome outcome = friends.accept(me, other.get());
                say(player, "friends." + key(outcome), "name", theirs);
                if (outcome == Friends.Outcome.ACCEPTED) {
                    socials.players().tell(other.get(), "friends.now-friends", "name", mine);
                }
            }
            case "deny" -> say(player, "friends." + key(friends.deny(me, other.get())), "name", theirs);
            case "remove" -> say(player, "friends." + key(friends.remove(me, other.get())), "name", theirs);
            case "join" -> {
                Optional<Presence.Here> there = socials.presence().where(other.get(), false);
                if (!friends.are(me, other.get()) || there.isEmpty()) {
                    say(player, "friends.not-online", "name", theirs);
                    return;
                }
                Plugin core = Bukkit.getPluginManager().getPlugin("RainsCore");
                if (core != null) {
                    Scheduling.entity(core, player, () -> ProxyTransfer.send(core, player, there.get().server()));
                }
            }
            default -> say(player, "friends.usage");
        }
    }

    private static void list(Socials socials, Player player) {
        List<UUID> all = socials.friends().of(player.getUniqueId());
        if (all.isEmpty()) {
            say(player, "friends.list-empty");
            return;
        }
        long online = all.stream().filter(friend -> socials.presence().where(friend, false).isPresent()).count();
        say(player, "friends.list-header", "online", String.valueOf(online), "total", String.valueOf(all.size()));
        for (UUID friend : all) {
            Optional<Presence.Here> there = socials.presence().where(friend, false);
            String name = socials.lastName(friend).orElse("?");
            if (there.isPresent()) {
                String server = socials.players().servers().stream()
                        .filter(each -> each.id().equals(there.get().server())).map(NetworkServers.Server::name)
                        .findFirst().orElse(there.get().server());
                say(player, "friends.list-online", "name", name, "server", server);
            } else {
                say(player, "friends.list-offline", "name", name);
            }
        }
    }

    static String key(Friends.Outcome outcome) {
        return outcome.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static void say(Player player, String key, Object... values) {
        Plugin core = Bukkit.getPluginManager().getPlugin("RainsCore");
        if (core == null) {
            return;
        }
        Scheduling.entity(core, player, () -> RainsCore.get().messages().send(player, key, values));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(option -> option.startsWith(typed)).toList();
        }
        if (args.length == 2) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            return Socials.installed().map(socials -> socials.presence().everyone(false).stream()
                    .map(Presence.Here::name).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                    .toList()).orElse(List.of());
        }
        return List.of();
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender instanceof Player;
    }
}
