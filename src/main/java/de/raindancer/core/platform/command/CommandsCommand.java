package de.raindancer.core.platform.command;

import de.raindancer.core.ui.text.Text;
import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.util.Closest;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

/**
 * {@code /commands} — the directory, as a book.
 *
 * <h2>Why it filters</h2>
 * A reader is shown what they may use. Not greyed: absent. A directory that lists every staff command
 * to every player is a directory that teaches the server's whole moderation vocabulary to somebody
 * who cannot run any of it, and the first thing that follows is thirty "you may not do that" messages
 * from people finding out one at a time.
 *
 * <h2>Why the console gets lines instead</h2>
 * A console cannot open a book. It gets the same directory printed, which is also the form somebody
 * grepping a log wants.
 *
 * <p>Nothing is held: registered from a bootstrapper, before anything exists. Everything is looked up
 * when the command is actually run.
 */
public final class CommandsCommand implements BasicCommand {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    @Override
    public boolean canUse(CommandSender sender) {
        // Deliberately everybody. The filtering is per entry, so the answer is never an empty book —
        // every player has at least the commands nothing guards.
        return true;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!RainsCore.isAvailable()) {
            return;
        }
        CommandDirectory directory = RainsCore.get().commands();

        String wanted = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : null;
        List<CommandNote> mine = directory.visibleTo(sender::hasPermission);
        List<CommandNote> visible = matching(mine, wanted);
        if (visible.isEmpty() && wanted != null) {
            // An empty book teaches nothing: say so, and what was probably meant.
            sender.sendMessage(MINI.deserialize("<gray>None of your commands mention <white>"
                    + Text.literal(wanted) + "</white>."));
            List<String> close = Closest.to(wanted, namesIn(mine), 3);
            sender.sendMessage(MINI.deserialize(close.isEmpty()
                    ? "<gray>Try <white>/commands</white> on its own for all of them."
                    : "<gray>Did you mean <white>" + Text.literal(String.join(", ", close))
                    + "</white>? Or <white>/commands</white> on its own for all of them."));
            return;
        }

        if (sender instanceof Player reader) {
            reader.openBook(new CommandBook(visible, title(wanted)).asBook());
            return;
        }
        // The console, or anything else that is not holding a book.
        sender.sendMessage(MINI.deserialize("<dark_aqua>" + visible.size() + " command(s):"));
        for (CommandNote note : visible) {
            sender.sendMessage(MINI.deserialize("<blue>" + note.slashed() + "<gray>: "
                    + Text.literal(note.sentence())));
            for (String option : note.options()) {
                sender.sendMessage(MINI.deserialize("<dark_gray>    " + Text.literal(option)));
            }
        }
    }

    /** The notes whose plugin or command mentions what was asked for; all of them for nothing asked. */
    static List<CommandNote> matching(List<CommandNote> notes, String wanted) {
        return notes.stream()
                .filter(note -> wanted == null
                        || note.plugin().toLowerCase(Locale.ROOT).contains(wanted)
                        || note.command().contains(wanted))
                .toList();
    }

    /** Plugin names and command names, each once — what can be asked for. */
    static List<String> namesIn(List<CommandNote> notes) {
        Set<String> names = new LinkedHashSet<>();
        for (CommandNote note : notes) {
            names.add(note.plugin());
        }
        for (CommandNote note : notes) {
            String command = note.command();
            int space = command.indexOf(' ');
            names.add(space < 0 ? command : command.substring(0, space));
        }
        return List.copyOf(names);
    }

    private static String title(String wanted) {
        return wanted == null ? "Commands" : "Commands: " + wanted;
    }

    /**
     * The plugins and commands this sender can use, so {@code /commands wa<tab>} finds Warps and
     * {@code /commands ho<tab>} finds home.
     */
    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1 || !RainsCore.isAvailable()) {
            return List.of();
        }
        String typed = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        return namesIn(RainsCore.get().commands().visibleTo(source.getSender()::hasPermission)).stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                .toList();
    }
}
