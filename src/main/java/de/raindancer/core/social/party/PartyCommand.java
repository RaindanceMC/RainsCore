package de.raindancer.core.social.party;

import de.raindancer.core.platform.network.NetworkServers;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.friends.Socials;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /party} — a party across the network that follows its leader from server to server: {@code invite|accept|kick
 * <name>}, {@code leave}, {@code list}, {@code chat <message>}; {@code /pc <message>} is the chat alone.
 */
public final class PartyCommand implements BasicCommand {

    static final List<String> SUBCOMMANDS = List.of("invite", "accept", "kick", "leave", "list", "chat");

    private final boolean chatOnly;

    public PartyCommand(boolean chatOnly) {
        this.chatOnly = chatOnly;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("Only players are in parties.");
            return;
        }
        Optional<Socials> socials = Socials.installed();
        Plugin core = Bukkit.getPluginManager().getPlugin("RainsCore");
        if (socials.isEmpty() || core == null) {
            say(socials, player, "party.off");
            return;
        }
        String what = chatOnly ? "chat" : args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = chatOnly ? args : args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        Scheduling.async(core, () -> run(socials.get(), player, what, rest));
    }

    private void run(Socials socials, Player player, String what, String[] rest) {
        UUID me = player.getUniqueId();
        Parties parties = socials.parties();
        String name = rest.length > 0 ? rest[0] : "";
        switch (what) {
            case "list" -> list(socials, player);
            case "leave" -> {
                Optional<Parties.Party> party = parties.of(me);
                Parties.Outcome outcome = parties.leave(me);
                say(socials, player, outcome == Parties.Outcome.LEFT ? "party.left" : "party." + key(outcome));
                if (outcome == Parties.Outcome.LEFT) {
                    party.get().members().stream().filter(member -> !member.equals(me)).forEach(member ->
                            socials.players().tell(member, "party.member-left", "name", player.getName()));
                }
            }
            case "chat" -> {
                Optional<Parties.Party> party = parties.of(me);
                if (party.isEmpty()) {
                    say(socials, player, "party.not-in-one");
                    return;
                }
                String message = String.join(" ", rest).strip();
                if (message.isEmpty()) {
                    say(socials, player, "party.usage");
                    return;
                }
                party.get().members().forEach(member -> socials.players().tell(member, "party.chat",
                        "name", player.getName(), "message", message));
            }
            case "invite", "accept", "kick" -> {
                if (name.isBlank()) {
                    say(socials, player, "party.usage");
                    return;
                }
                Optional<UUID> other = what.equals("invite")
                        ? socials.presence().named(name, false).map(Presence.Here::player) : socials.idOf(name);
                if (other.isEmpty()) {
                    say(socials, player, "party.not-online", "name", name);
                    return;
                }
                String theirs = socials.lastName(other.get()).orElse(name);
                switch (what) {
                    case "invite" -> {
                        Parties.Outcome outcome = parties.invite(me, other.get());
                        say(socials, player, outcome == Parties.Outcome.INVITED ? "party.invite-sent" : "party." + key(outcome),
                                "name", theirs);
                        if (outcome == Parties.Outcome.INVITED) {
                            socials.players().tell(other.get(), "party.invited", "name", player.getName());
                        }
                    }
                    case "accept" -> {
                        Parties.Outcome outcome = parties.accept(me, other.get());
                        say(socials, player, outcome == Parties.Outcome.JOINED ? "party.joined" : "party." + key(outcome),
                                "name", theirs);
                        if (outcome == Parties.Outcome.JOINED) {
                            parties.of(me).ifPresent(party -> party.members().stream().filter(member -> !member.equals(me))
                                    .forEach(member -> socials.players().tell(member, "party.member-joined",
                                            "name", player.getName())));
                        }
                    }
                    default -> {
                        Parties.Outcome outcome = parties.kick(me, other.get());
                        say(socials, player, outcome == Parties.Outcome.KICKED ? "party.kicked" : "party." + key(outcome),
                                "name", theirs);
                        if (outcome == Parties.Outcome.KICKED) {
                            socials.players().tell(other.get(), "party.you-were-kicked");
                        }
                    }
                }
            }
            default -> say(socials, player, "party.usage");
        }
    }

    private static void list(Socials socials, Player player) {
        Optional<Parties.Party> party = socials.parties().of(player.getUniqueId());
        if (party.isEmpty()) {
            List<UUID> invites = socials.parties().invitesTo(player.getUniqueId());
            say(Optional.of(socials), player, invites.isEmpty() ? "party.none" : "party.none-but-invited",
                    "names", String.join(", ", invites.stream().map(id -> socials.lastName(id).orElse("?")).toList()));
            return;
        }
        say(Optional.of(socials), player, "party.list-header", "count", String.valueOf(party.get().members().size()),
                "most", String.valueOf(Parties.MOST));
        for (UUID member : party.get().members()) {
            Optional<Presence.Here> there = socials.presence().where(member, false);
            String server = there.map(here -> socials.players().servers().stream()
                    .filter(each -> each.id().equals(here.server())).map(NetworkServers.Server::name).findFirst()
                    .orElse(here.server())).orElse("offline");
            say(Optional.of(socials), player, member.equals(party.get().leader()) ? "party.list-leader" : "party.list-line",
                    "name", socials.lastName(member).orElse("?"), "server", server);
        }
    }

    private static String key(Parties.Outcome outcome) {
        return outcome.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static void say(Optional<Socials> socials, Player player, String key, Object... values) {
        Plugin core = Bukkit.getPluginManager().getPlugin("RainsCore");
        if (core == null) {
            return;
        }
        Scheduling.entity(core, player, () -> de.raindancer.core.RainsCore.get().messages().send(player, key, values));
    }

    private static void say(Socials socials, Player player, String key, Object... values) {
        say(Optional.ofNullable(socials), player, key, values);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (chatOnly) {
            return List.of();
        }
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(option -> option.startsWith(typed)).toList();
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("chat")) {
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
