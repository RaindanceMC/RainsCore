package de.raindancer.core.data.settings;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.prompt.ChatPrompts;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import de.raindancer.core.RainsCore;

import java.time.Duration;
import java.util.UUID;

/**
 * Typing a settings value in chat.
 *
 * <h2>Why chat and not an anvil</h2>
 * An anvil rename is the usual trick and it is worse here: it cannot show what the value is now, nor
 * what it is allowed to be, and both matter more than not leaving the window. So the menu closes,
 * says what it wants along with the current value and the range, and waits for one line.
 *
 * <h2>Why it does not listen to chat itself</h2>
 * It used to, and that was a bug waiting for company: the claims module and the Hunger Games each
 * had their own chat-input listener too, and three listeners all claiming the next line a player
 * types is three plugins fighting over one answer. {@link ChatPrompts} owns that now — this only
 * asks. The line still never reaches chat, and the reply still happens on a thread that may touch
 * the world, because a chat event's does not.
 */
public final class SettingsChatInput {


    /** Who is being asked for what, and which page to send them back to. */
    private record Waiting(String key, String returnTo) {
    }

    /** How long somebody is given to type a value before the question is dropped. */
    private static final Duration PATIENCE = Duration.ofMinutes(2);

    private static volatile SettingsChatInput instance;

    private final Plugin plugin;
    private final SettingsNavigation navigation;
    private final Chat chat;
    private final Brand brand;
    private final ChatPrompts prompts;

    public SettingsChatInput(Plugin plugin, SettingsNavigation navigation, Chat chat, Brand brand,
                             ChatPrompts prompts) {
        this.plugin = plugin;
        this.navigation = navigation;
        this.chat = chat;
        this.brand = brand;
        this.prompts = prompts;
        instance = this;
    }

    /** Called by the menu: the next line this player types is a value for this setting. */
    static boolean expect(Player player, String key, String returnTo) {
        SettingsChatInput input = instance;
        if (input == null) {
            return false;
        }
        Waiting waiting = new Waiting(key, returnTo);
        return input.prompts.ask(player.getUniqueId(), "settings", PATIENCE,
                typed -> input.applyLater(player, waiting, typed),
                () -> input.chat.tell(player, "<gray>Left it as it was."));
    }

    /** Whether the settings are waiting on somebody — for a diagnostic, and for the tests. */
    public static boolean isWaiting(UUID player) {
        SettingsChatInput input = instance;
        return input != null && input.prompts.waitingFor(player)
                .map("settings"::equals).orElse(false);
    }

    /** Back onto a thread that may open an inventory: a chat event's is not one. */
    private void applyLater(Player player, Waiting waiting, String typed) {
        // On the player's own thread, not the global one. Everything this does ends in
        // player.openInventory(), and on Folia touching a player from a region that does not own them
        // throws — which would break the settings menu for anybody not standing in the global region.
        Scheduling.entity(plugin, player, () -> apply(player, waiting, typed));
    }

    private void apply(Player player, Waiting waiting, String typed) {
        if (!navigation.registry().mayChange(player, waiting.key())) {
            chat.no(player, "<red>Changing that for every server needs more; it is left as it was.");
            return;
        }
        if (navigation.registry().set(waiting.key(), typed)) {
            SettingsSaving.saveThenTell(navigation.registry(), player);
            chat.ok(player, words().raw("settings.changed"),
                    Chat.arg("name", waiting.key()),
                    Chat.arg("value", navigation.registry().display(waiting.key())));
        } else {
            // Refused rather than clamped, and told why: whoever typed this is standing there.
            chat.no(player, words().raw("settings.refused"),
                    Chat.arg("value", typed),
                    Chat.arg("name", waiting.key()));
            navigation.registry().setting(waiting.key()).ifPresent(setting -> {
                if (setting.min() != null) {
                    chat.row(player, "<dark_gray>  it goes from " + setting.min()
                            + " to " + setting.max());
                } else if (!setting.choices().isEmpty()) {
                    chat.row(player, "<dark_gray>  one of: "
                            + String.join(", ", setting.choices()));
                }
            });
        }
        reopen(player, waiting);
    }

    private void reopen(Player player, Waiting waiting) {
        new SettingsMenu(player, brand, chat, navigation, waiting.returnTo(), null).open();
    }


    /**
     * Where the wording comes from.
     *
     * <p>Asked for each time rather than held, because this is built while the plugin is still
     * starting and the message file may be read again afterwards.
     */
    private static Messages words() {
        return RainsCore.get().messages();
    }

}
