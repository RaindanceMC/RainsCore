package de.raindancer.core.ui.checklist;

import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A checklist in chat — for the console, a command, or anybody who would rather not open a window.
 * Each red line that can be fixed carries a clickable [fix] for the player reading it.
 */
public final class ChecklistChat {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Duration FIX_VALID_FOR = Duration.ofMinutes(5);

    private ChecklistChat() {
    }

    /** The lines, without sending them — buttons only when {@code buttons} can make them clickable. */
    public static List<Component> lines(Checklist list, UUID reader, ChatButtons buttons) {
        List<Component> lines = new ArrayList<>();
        lines.add(MINI.deserialize("<" + Style.titleLabel() + ">" + Text.literal(list.title())
                + "<dark_gray>: " + (list.ready() ? "<" + Style.ok() + ">" : "<" + Style.bad() + ">")
                + Text.literal(list.summary())));
        for (Map.Entry<String, List<Checklist.Check>> group : list.byGroup().entrySet()) {
            if (!group.getKey().isEmpty()) {
                lines.add(MINI.deserialize("<dark_gray>" + Text.literal(group.getKey())));
            }
            for (Checklist.Check check : group.getValue()) {
                lines.add(line(check, reader, buttons));
            }
        }
        return lines;
    }

    /** Sends the list to whoever asked. */
    public static void tell(Audience reader, Checklist list, ChatButtons buttons) {
        UUID who = reader instanceof Player player ? player.getUniqueId() : null;
        lines(list, who, buttons).forEach(reader::sendMessage);
    }

    private static Component line(Checklist.Check check, UUID reader, ChatButtons buttons) {
        String colour = check.ok() ? Style.ok() : check.blocks() ? Style.bad() : Style.warn();
        String mark = check.ok() ? "✔" : check.blocks() ? "✘" : "⚠";
        Component line = MINI.deserialize(" <" + colour + ">" + mark + " " + Text.literal(check.label())
                + (check.detail().isEmpty() ? "" : "<dark_gray>: <gray>" + Text.literal(check.detail())));
        if (reader == null || buttons == null || !buttons.isClickable()) {
            return line;
        }
        Player player = Bukkit.getPlayer(reader);
        return check.fixIfAny().filter(fix -> fix.allowedFor(player)).map(fix -> line.append(Component.space())
                .append(buttons.label("<" + Style.ok() + ">[" + Text.literal(fix.label()) + "]")
                        .tooltip("<gray>Click to fix this")
                        .forOnly(reader)
                        .expiringIn(FIX_VALID_FOR)
                        .does(clicker -> {
                            Player online = Bukkit.getPlayer(clicker);
                            if (online != null) {
                                fix.action().accept(online);
                            }
                        })
                        .render()))
                .orElse(line);
    }
}
