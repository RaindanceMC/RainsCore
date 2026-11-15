package de.raindancer.core.moderation.vanish;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.ChatButton;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Holds what a vanished player says — in chat, or through a messaging command — and asks first:
 * [Send anyway], [Send, and stop asking] until they reappear, or [Never mind].
 *
 * <p>{@code LOWEST}, so it runs before any chat plugin formats, logs, bridges or routes the line: a held
 * line has been seen by nobody, not even the console or a Discord bridge.
 */
public final class VanishChatGuard implements Listener {

    private final Plugin plugin;
    private final Vanish vanish;
    private final VanishedSpeech speech;
    private final Messages messages;
    private final ChatButtons buttons;

    public VanishChatGuard(Plugin plugin, Vanish vanish, VanishedSpeech speech, Messages messages,
                           ChatButtons buttons) {
        this.plugin = plugin;
        this.vanish = vanish;
        this.speech = speech;
        this.messages = messages;
        this.buttons = buttons;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String line = PlainTextComponentSerializer.plainText().serialize(event.originalMessage());
        if (!speech.shouldHold(player.getUniqueId(), vanish.isVanished(player.getUniqueId()), line)) {
            return;
        }
        event.setCancelled(true);
        ask(player, line, false);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String line = event.getMessage();
        if (VanishedSpeech.speaking(line).isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        if (!speech.shouldHold(player.getUniqueId(), vanish.isVanished(player.getUniqueId()), line)) {
            return;
        }
        event.setCancelled(true);
        ask(player, line, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        speech.forget(event.getPlayer().getUniqueId());
    }

    private void ask(Player player, String line, boolean command) {
        UUID id = player.getUniqueId();
        ChatButton send = buttons.label("<green>[Send anyway]</green>")
                .tooltip("<gray>Everybody will see it and know you are here")
                .forOnly(id).expiringIn(VanishedSpeech.ALLOW_FOR)
                .does(clicker -> resend(player, line, command));
        ChatButton sendAndStop = buttons.label("<yellow>[Send, and stop asking]</yellow>")
                .tooltip("<gray>Sends it, and anything else you say until you reappear")
                .forOnly(id).expiringIn(VanishedSpeech.ALLOW_FOR)
                .does(clicker -> {
                    speech.stopAsking(id);
                    messages.send(player, "vanish-chat.not-asking");
                    resend(player, line, command);
                });
        ChatButton keep = buttons.label("<gray>[Never mind]</gray>")
                .tooltip("<gray>Nobody will know")
                .forOnly(id).expiringIn(VanishedSpeech.ALLOW_FOR)
                .does(clicker -> messages.send(player, "vanish-chat.kept"));
        player.sendMessage(messages.prefixed("vanish-chat.held", "line", line)
                .appendNewline().append(buttons.row(send, sendAndStop, keep)));
    }

    private void resend(Player player, String line, boolean command) {
        Scheduling.onOwner(plugin, player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (command) {
                // performCommand does not go through the preprocess event again, so nothing to let through.
                player.performCommand(line.startsWith("/") ? line.substring(1) : line);
            } else {
                speech.allowOnce(player.getUniqueId(), line);
                player.chat(line);
            }
        });
    }
}
