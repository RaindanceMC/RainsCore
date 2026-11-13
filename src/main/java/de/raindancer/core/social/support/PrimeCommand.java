package de.raindancer.core.social.support;

import de.raindancer.core.RainsCore;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@code /prime} — what a player sees is the registering module's ({@code view}); the words below are the shop's and
 * staff's:
 *
 * <pre>
 * /prime grant &lt;player|uuid&gt; &lt;days&gt;   on every purchase and renewal (Tebex: prime grant {id} 31)
 * /prime revoke &lt;player|uuid&gt;          on expiry or a chargeback (Tebex: prime revoke {id})
 * /prime info &lt;player|uuid&gt;
 * </pre>
 * A uuid works for somebody who has never joined — a shop can sell before the first login.
 */
public final class PrimeCommand implements BasicCommand {

    public static final String ADMIN = "rains.prime.admin";
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final List<String> WORDS = List.of("grant", "revoke", "info");
    private static final Pattern BARE_UUID = Pattern.compile("[0-9a-fA-F]{32}");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final Consumer<CommandSourceStack> view;

    public PrimeCommand(Consumer<CommandSourceStack> view) {
        this.view = view;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String word = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!WORDS.contains(word) || !sender.hasPermission(ADMIN)) {
            view.accept(source);
            return;
        }
        if (args.length < 2 || (word.equals("grant") && args.length < 3)) {
            say(sender, "<gray>Usage: /prime grant <player|uuid> <days> · /prime revoke <player|uuid> · /prime info <player|uuid>");
            return;
        }
        Optional<UUID> target = target(args[1]);
        if (target.isEmpty()) {
            say(sender, "<red>Nobody called <name> has been here. Use their UUID (Tebex: {id}).",
                    Placeholder.unparsed("name", args[1]));
            return;
        }
        RainsCore core = RainsCore.get();
        PrimeMembers members = core.prime();
        UUID who = target.get();
        Instant now = Instant.now();
        switch (word) {
            case "grant" -> {
                Optional<Duration> length = days(args[2]);
                if (length.isEmpty()) {
                    say(sender, "<red>'<days>' is not a number of days (1–3660).", Placeholder.unparsed("days", args[2]));
                    return;
                }
                PrimeMembers.Member member = members.grant(who, length.get(), now);
                say(sender, "<green><name> is Prime until <until>.", Placeholder.unparsed("name", nameOf(who)),
                        Placeholder.unparsed("until", DAY.format(member.until())));
                audit(core, sender, who, "granted Prime for " + length.get().toDays() + " days");
                applied(core, who);
            }
            case "revoke" -> {
                if (members.revoke(who, now)) {
                    say(sender, "<yellow><name> is no longer Prime.", Placeholder.unparsed("name", nameOf(who)));
                    audit(core, sender, who, "ended Prime");
                    applied(core, who);
                } else {
                    say(sender, "<gray><name> was not Prime.", Placeholder.unparsed("name", nameOf(who)));
                }
            }
            default -> {
                Optional<PrimeMembers.Member> member = members.of(who);
                if (member.isEmpty()) {
                    say(sender, "<gray><name> has never been Prime.", Placeholder.unparsed("name", nameOf(who)));
                    return;
                }
                PrimeMembers.Member had = member.get();
                say(sender, "<gray><name>: " + (had.activeAt(now) ? "<green>Prime</green> until " : "Prime ended ")
                                + "<until>, this run since <since>. Months: <months>.",
                        Placeholder.unparsed("name", nameOf(who)),
                        Placeholder.unparsed("until", DAY.format(had.until())),
                        Placeholder.unparsed("since", DAY.format(had.since())),
                        Placeholder.unparsed("months", had.months().toString()));
            }
        }
    }

    /** Written at once (the shop's command is the source of truth) and applied to whoever is online here. */
    private static void applied(RainsCore core, UUID who) {
        org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("RainsCore");
        if (plugin == null) {
            return;
        }
        Scheduling.async(plugin, () -> {
            core.prime().save();
            core.primePermission().reconcileGroup();
        });
        Player online = Bukkit.getPlayer(who);
        if (online != null) {
            Scheduling.entity(plugin, online, () -> core.primePermission().sync(online));
        }
    }

    static Optional<UUID> target(String typed) {
        String text = typed.trim();
        if (BARE_UUID.matcher(text).matches()) {
            text = text.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                    "$1-$2-$3-$4-$5");
        }
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException notAUuid) {
            return Bukkit.getServer() == null ? Optional.empty() : PlayerTargets.idOf(Bukkit.getServer(), text);
        }
    }

    static Optional<Duration> days(String typed) {
        try {
            int days = Integer.parseInt(typed.trim().toLowerCase(Locale.ROOT).replaceFirst("d$", ""));
            return days >= 1 && days <= 3660 ? Optional.of(Duration.ofDays(days)) : Optional.empty();
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    private static String nameOf(UUID who) {
        String name = Bukkit.getOfflinePlayer(who).getName();
        return name == null ? who.toString() : name;
    }

    private static void audit(RainsCore core, CommandSender sender, UUID who, String what) {
        AuditEntry.Builder entry = AuditEntry.of("prime", what).to(who, nameOf(who));
        if (sender instanceof Player player) {
            entry.by(player.getUniqueId(), player.getName());
        }
        core.audit().record(entry);
    }

    private static void say(CommandSender sender, String text, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        sender.sendMessage(MINI.deserialize(text, resolvers));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!source.getSender().hasPermission(ADMIN)) {
            return List.of();
        }
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return WORDS.stream().filter(word -> word.startsWith(typed)).toList();
        }
        if (args.length == 2) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return args.length == 3 && args[0].equalsIgnoreCase("grant") ? List.of("31", "365") : List.of();
    }
}
