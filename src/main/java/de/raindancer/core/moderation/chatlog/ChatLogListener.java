package de.raindancer.core.moderation.chatlog;

import de.raindancer.core.ui.messages.Messages;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.function.IntSupplier;

/**
 * Public chat into the log — only what was actually said: a line cancelled by a mute, a filter or a channel
 * that delivers it by hand never reaches {@code MONITOR}. Channels log their own lines through {@link ChatLog}.
 * And a first join is told the log exists, which is what makes keeping it fair.
 */
public final class ChatLogListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final ChatLog log;
    private final Messages messages;
    private final IntSupplier retentionDays;

    public ChatLogListener(ChatLog log, Messages messages, IntSupplier retentionDays) {
        this.log = log;
        this.messages = messages;
        this.retentionDays = retentionDays;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        log.record(event.getPlayer().getUniqueId(), event.getPlayer().getName(), ChatLog.PUBLIC,
                PLAIN.serialize(event.message()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // With the welcome book, its "Your data" chapter says this instead.
        if (log.isEnabled() && !event.getPlayer().hasPlayedBefore()
                && !de.raindancer.core.ui.book.WelcomeBook.takesOver()) {
            messages.sendPlain(event.getPlayer(), "chatlog.notice", "days", retentionDays.getAsInt());
        }
    }
}
