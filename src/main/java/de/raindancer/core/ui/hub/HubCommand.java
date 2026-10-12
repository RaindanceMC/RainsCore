package de.raindancer.core.ui.hub;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.command.CommandNote;
import de.raindancer.core.ui.text.Text;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /hub} opens the hub; {@code /hub <feature>} opens that feature's page. The console gets the same
 * information as lines.
 */
public final class HubCommand implements BasicCommand {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    @Override
    public boolean canUse(CommandSender sender) {
        return true;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!RainsCore.isAvailable()) {
            return;
        }
        RainsCore core = RainsCore.get();
        List<HubEntry> visible = core.hub().visibleTo(sender::hasPermission, core.commands());
        String typed = String.join(" ", args).trim();
        Optional<HubEntry> wanted = Hub.find(visible, typed);

        if (!typed.isEmpty() && wanted.isEmpty()) {
            sender.sendMessage(MINI.deserialize("<gray>No feature here is called <white>" + Text.literal(typed)
                    + "</white>. Try <white>/hub</white> to see them all."));
            return;
        }
        if (sender instanceof Player player) {
            var brand = core.chatFor("Hub").brand();
            if (wanted.isPresent()) {
                HubMenu root = new HubMenu(player, brand);
                new HubModuleMenu(player, brand, root, wanted.get()).open();
            } else {
                new HubMenu(player, brand).open();
            }
            return;
        }
        printTo(sender, wanted.map(List::of).orElse(visible), core);
    }

    private static void printTo(CommandSender sender, List<HubEntry> entries, RainsCore core) {
        sender.sendMessage(MINI.deserialize("<dark_aqua>" + entries.size() + " feature(s):"));
        for (HubEntry entry : entries) {
            sender.sendMessage(MINI.deserialize("<blue>" + Text.literal(entry.name()) + " <dark_gray>"
                    + Text.literal(entry.version()) + " <gray>— " + Text.literal(entry.description())));
            for (CommandNote note : Hub.notesFor(entry, core.commands(), sender::hasPermission)) {
                sender.sendMessage(MINI.deserialize("<dark_gray>    " + Text.literal(note.slashed()) + " — "
                        + Text.literal(note.sentence())));
            }
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1 || !RainsCore.isAvailable()) {
            return List.of();
        }
        String typed = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        RainsCore core = RainsCore.get();
        return core.hub().visibleTo(source.getSender()::hasPermission, core.commands()).stream()
                .map(HubEntry::id)
                .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(typed))
                .toList();
    }
}
