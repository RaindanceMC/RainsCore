package de.raindancer.core.ui.help;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * Makes {@code /help} and {@code /?} the guide's rather than Bukkit's.
 *
 * <p>Bukkit registers its own {@code /help} — the paged list of command names — after plugins have registered theirs,
 * and on a real server it wins the bare name: the guide was registered, reachable as {@code /<plugin>:help}, and
 * nobody typing {@code /help} ever met it. Found by a bot on a real Paper server; nothing below the server line can
 * see it. So once the server has loaded, the bare names are pointed at the guide. Bukkit's list stays reachable as
 * {@code /bukkit:help} for anybody who wants it.
 */
final class HelpTakeover {

    private static final LogChannel log = Log.of("help");

    private HelpTakeover() {
    }

    /** @return how many names now lead to the guide */
    static int takeOver(CommandMap map) {
        Map<String, Command> known = map.getKnownCommands();
        Command guide = null;
        for (Map.Entry<String, Command> entry : known.entrySet()) {
            Command command = entry.getValue();
            if (entry.getKey().endsWith(":help") && !(command instanceof org.bukkit.command.defaults.HelpCommand)
                    && HelpCommand.DESCRIPTION.equals(command.getDescription())) {
                guide = command;
                break;
            }
        }
        if (guide == null) {
            log.warn("No plugin registered the guide's /help (a module plugin built with modules-wrapper 1.9.0 or "
                    + "newer does), so /help stays Bukkit's list.");
            return 0;
        }
        int taken = 0;
        for (String name : List.of("help", "?")) {
            Command there = known.get(name);
            if (there == guide) {
                taken++;
                continue;
            }
            // Only Bukkit's own list is replaced: another plugin's /help is somebody's choice.
            if (there == null || there instanceof org.bukkit.command.defaults.HelpCommand) {
                known.put(name, guide);
                taken++;
            } else {
                log.info("/{} belongs to another plugin ({}); the guide stays at /guide.", name, there.getClass().getName());
            }
        }
        Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
        return taken;
    }
}
