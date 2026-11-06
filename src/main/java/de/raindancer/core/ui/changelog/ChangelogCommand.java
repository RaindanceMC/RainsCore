package de.raindancer.core.ui.changelog;

import de.raindancer.core.RainsCore;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code /changelog} — what changed lately. For staff ({@value #MANAGE}) also:
 *
 * <pre>
 * /changelog preview [id]   the drafts, as players would see them
 * /changelog publish &lt;id&gt;   out it goes: everybody online now, everybody else when they come back
 * /changelog reload         after editing changelog.yml
 * </pre>
 */
public final class ChangelogCommand implements BasicCommand {

    public static final String MANAGE = "rainscore.changelog.manage";
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int SHOWN = 5;

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Changelog changelog = RainsCore.get().changelog();
        ZoneId zone = ZoneId.systemDefault();
        String word = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!word.isEmpty() && !sender.hasPermission(MANAGE)) {
            word = "";
        }
        switch (word) {
            case "preview" -> {
                List<ChangelogEntry> drafts = args.length > 1
                        ? changelog.entry(args[1]).stream().toList()
                        : changelog.drafts();
                if (drafts.isEmpty()) {
                    say(sender, args.length > 1 ? "<red>No entry called " + MINI.escapeTags(args[1]) + "."
                            : "<gray>No drafts. Write one in plugins/RainsCore/changelog.yml, then /changelog reload.");
                    return;
                }
                sender.sendMessage(ChangelogView.render(drafts, "Preview", zone));
            }
            case "publish" -> {
                if (args.length < 2) {
                    say(sender, "<red>/changelog publish <id>");
                    return;
                }
                String id = args[1];
                Scheduling.async(core(), () -> {
                    switch (changelog.publish(id)) {
                        case UNKNOWN -> say(sender, "<red>No entry called " + MINI.escapeTags(id) + ".");
                        case ALREADY -> say(sender, "<yellow>That one is out already.");
                        case PUBLISHED, NOT_SAVED -> announce(changelog, id, sender, zone);
                    }
                });
            }
            case "reload" -> {
                Scheduling.async(core(), () -> {
                    changelog.reload();
                    say(sender, "<green>changelog.yml read: " + changelog.drafts().size() + " draft(s), "
                            + changelog.published().size() + " published.");
                    changelog.problems().forEach(problem -> say(sender, "<red>" + MINI.escapeTags(problem)));
                });
            }
            default -> {
                List<ChangelogEntry> published = changelog.published();
                if (published.isEmpty()) {
                    say(sender, "<gray>Nothing new to tell yet.");
                    return;
                }
                sender.sendMessage(ChangelogView.render(published.subList(0, Math.min(SHOWN, published.size())),
                        "What's new", zone));
            }
        }
    }

    private static void announce(Changelog changelog, String id, CommandSender sender, ZoneId zone) {
        ChangelogEntry entry = changelog.entry(id).orElseThrow();
        for (Player online : Bukkit.getOnlinePlayers()) {
            changelog.seen(online.getUniqueId());
            Scheduling.entity(core(), online,
                    () -> online.sendMessage(ChangelogView.render(List.of(entry), "What's new", zone)));
        }
        if (!(sender instanceof Player)) {
            say(sender, "<green>Published " + MINI.escapeTags(id) + "; told " + Bukkit.getOnlinePlayers().size()
                    + " player(s) online, the rest when they come back.");
        }
        AuditEntry.Builder audit = AuditEntry.of("core", "published a changelog entry").saying(id);
        if (sender instanceof Player player) {
            audit.by(player.getUniqueId(), player.getName());
        }
        RainsCore.get().audit().record(audit);
    }

    private static org.bukkit.plugin.Plugin core() {
        return Bukkit.getPluginManager().getPlugin("RainsCore");
    }

    private static void say(CommandSender sender, String miniMessage) {
        sender.sendMessage(MINI.deserialize(miniMessage));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!source.getSender().hasPermission(MANAGE)) {
            return List.of();
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        Stream<String> options = args.length <= 1
                ? Stream.of("preview", "publish", "reload")
                : args.length == 2 && List.of("preview", "publish").contains(args[0].toLowerCase(Locale.ROOT))
                ? RainsCore.get().changelog().drafts().stream().map(ChangelogEntry::id)
                : Stream.empty();
        return options.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }
}
