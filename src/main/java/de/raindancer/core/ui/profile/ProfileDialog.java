package de.raindancer.core.ui.profile;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.dialog.DialogPage;
import de.raindancer.core.ui.dialog.DialogReturn;
import de.raindancer.core.ui.dialog.Faces;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.text.Text;
import de.raindancer.core.world.time.Times;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A player's profile as the game's own window, like /help: their face big, online or when they were last here, how
 * long they played, and whatever the installed modules offer about them ({@link ProfileExtension}) as buttons.
 * Anybody may look at anybody's — nothing here is more than a name in chat already tells.
 *
 * <p>Vanish-safe as {@link ProfileMenu} always was: somebody vanished from the viewer reads as gone since their last
 * login. A module's button gets a parent page whose Back and refresh bring this window back, so its own chest pages
 * keep working unchanged.
 */
public final class ProfileDialog {

    static final String HEADING = "<gradient:#C084FC:#7DD3FC>Profile</gradient>";

    /** How long somebody played: all of it, actively, and away. */
    record Played(Duration all, Duration active, Duration away) {
    }

    private ProfileDialog() {
    }

    /** Fetches their face, then shows the window on the viewer's thread. */
    public static void open(Player viewer, Brand brand, UUID subject, String subjectName, Runnable back) {
        String name = subjectName == null || subjectName.isBlank() ? "somebody" : subjectName;
        Faces.load(subject).thenRun(() -> Scheduling.entity(JavaPlugin.getProvidingPlugin(ProfileDialog.class), viewer,
                () -> {
                    if (viewer.isOnline()) {
                        OfflinePlayer who = Bukkit.getOfflinePlayer(subject);
                        page(viewer, brand, subject, name, who, facts(viewer, who), back).show(viewer);
                    }
                }));
    }

    static DialogPage page(Player viewer, Brand brand, UUID subject, String name, OfflinePlayer who, List<String> facts) {
        return page(viewer, brand, subject, name, who, facts, null);
    }

    static DialogPage page(Player viewer, Brand brand, UUID subject, String name, OfflinePlayer who, List<String> facts,
                           Runnable back) {
        DialogPage page = DialogPage.titled(HEADING, name).columns(2)
                .face(subject, () -> Icons.head(subject, "<white>" + Text.literal(name)),
                        "<white><bold>" + Text.literal(name) + "</bold>\n" + String.join("\n", facts));
        if (back != null) {
            page.back(back);
        }
        Menu parent = new DialogReturn(viewer, brand, name, () -> open(viewer, brand, subject, name, back));
        for (ProfileExtension extension : ProfileExtensions.all()) {
            ProfileButton button;
            try {
                button = extension.contribute(viewer, who, parent);
            } catch (RuntimeException misbehaving) {
                continue;   // one contributor's bug is not the whole window failing to open
            }
            if (button == null || button.icon() == null) {
                continue;
            }
            ItemStack icon = button.icon();
            String label = Text.literal(icon.effectiveName());
            List<Component> lore = icon.lore();
            String tooltip = lore == null || lore.isEmpty() ? null
                    : String.join("\n", lore.stream().map(Text::literal).toList());
            page.button(label, tooltip, answers -> {
                // A chest button's handler, pressed from a window: there is no inventory click to hand it.
                try {
                    button.onClick().accept(null);
                } catch (RuntimeException misbehaving) {
                    // Their bug; the window stays as it is.
                }
            });
        }
        return page;
    }

    /** What every profile says, from the server's own knowledge. */
    static List<String> facts(Player viewer, OfflinePlayer who) {
        boolean online = who.isOnline();
        boolean hidden = online && RainsCore.isAvailable()
                && !RainsCore.get().vanish().canSee(viewer.getUniqueId(), who.getUniqueId());
        Played played = null;
        if (RainsCore.isAvailable() && RainsCore.get().playtime().isKnown(who.getUniqueId())) {
            var playtime = RainsCore.get().playtime();
            played = new Played(playtime.played(who.getUniqueId()), playtime.playedActively(who.getUniqueId()),
                    playtime.playedAway(who.getUniqueId()));
        }
        return facts(who.hasPlayedBefore() || online, online, hidden, who.getLastLogin(), played, Instant.now());
    }

    static List<String> facts(boolean known, boolean online, boolean hiddenFromViewer, long lastLogin, Played played,
                              Instant now) {
        List<String> lines = new ArrayList<>();
        if (!known) {
            lines.add("<gray>This server has never seen them.");
            return lines;
        }
        if (online && !hiddenFromViewer) {
            lines.add("<green>Online right now.");
        } else {
            lines.add("<gray>Last seen <white>" + Times.describe(Duration.between(Instant.ofEpochMilli(lastLogin), now))
                    + "</white> ago.");
        }
        if (played != null) {
            lines.add("<gray>Played <white>" + Times.describe(played.all()) + "</white>.");
            lines.add("<gray>Active <white>" + Times.describe(played.active()) + "</white>, AFK <white>"
                    + Times.describe(played.away()) + "</white>.");
        }
        return lines;
    }
}
