package de.raindancer.core.ui.profile;

import de.raindancer.core.ui.text.Text;
import de.raindancer.core.RainsCore;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.world.time.Times;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One player, and everything installed on this server that has something to offer about them.
 *
 * <h2>Why this is Core's</h2>
 * Because it is nobody's page in particular. moderation-module already has one of these for staff —
 * {@code PlayerMenu}, punishments and all — and this is not a second copy of it: nothing here is a
 * punishment, a note, or anything a plain player should not see about somebody else. What is here is
 * exactly what {@link ProfileExtension} contributors choose to offer, plus a head and a last-seen
 * line every profile gets whether or not anything is installed at all.
 *
 * <h2>Vanish</h2>
 * A subject vanished from the viewer is shown exactly as {@code essentials-module}'s {@code /seen}
 * already shows one — present, but as though they had already logged off at their last real login —
 * because this page is reachable by clicking a name in ordinary chat text, which a plain player
 * reads all day, and "online right now" is precisely the fact vanish exists to hide.
 */
public final class ProfileMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final UUID subject;
    private final String subjectName;

    public ProfileMenu(Player viewer, Brand brand, Menu parent, UUID subject, String subjectName) {
        super(viewer, brand, parent);
        this.subject = subject;
        this.subjectName = subjectName == null || subjectName.isBlank() ? "somebody" : subjectName;
    }

    /**
     * Opens straight from a UUID and a server, resolving the name itself — what {@link ProfileCommand}
     * needs, since a click event carries only the id.
     */
    public static void open(Player viewer, Brand brand, UUID subject) {
        if (subject == null) {
            return;
        }
        String name = Bukkit.getOfflinePlayer(subject).getName();
        new ProfileMenu(viewer, brand, null, subject, name).open();
    }

    /**
     * Shown as the game's own window ({@link ProfileDialog}) — every way in, a clicked name, {@code /player} or a
     * module's list, lands there. {@code -Drains.anvil=true} keeps the chest, as for {@code ConfirmMenu}.
     */
    @Override
    public void open() {
        if (Boolean.getBoolean("rains.anvil")) {
            super.open();
            return;
        }
        Menu from = parent();
        ProfileDialog.open(viewer, brand(), subject, subjectName, from == null ? null : from::open);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">" + Text.literal(subjectName));
    }

    @Override
    public String breadcrumb() {
        return subjectName;
    }

    @Override
    protected void render() {
        band(MenuLayout.WHO, 4, Icons.head(subject, "<white>" + Text.literal(subjectName), headerLore()));

        int band = MenuLayout.WHO;
        int column = 1;
        for (ProfileExtension extension : ProfileExtensions.all()) {
            ProfileButton button;
            try {
                button = extension.contribute(viewer, offlineSubject(), this);
            } catch (RuntimeException misbehaving) {
                continue;   // one contributor's bug is not the whole page failing to open
            }
            if (button == null) {
                continue;
            }
            if (column == 4 && band == MenuLayout.WHO) {
                column = 5;   // the head sits at column 4 of the WHO band; buttons step around it
            }
            band(band, column, button.icon(), button.onClick());
            column++;
            if (column > 7) {
                column = 1;
                band = Math.min(MenuLayout.LAND, band + 1);
            }
        }
    }

    private OfflinePlayer offlineSubject() {
        return Bukkit.getOfflinePlayer(subject);
    }

    /**
     * Online, or how long ago they were — the one fact every profile shows, contributors or not.
     * Vanish-safe: see the class note.
     */
    private List<String> headerLore() {
        List<String> lore = new ArrayList<>();
        OfflinePlayer who = offlineSubject();
        if (!who.hasPlayedBefore()) {
            lore.add("<gray>This server has never seen them.");
            return lore;
        }
        boolean reallyOnline = who.isOnline();
        boolean hiddenFromViewer = reallyOnline && RainsCore.isAvailable()
                && !RainsCore.get().vanish().canSee(viewer.getUniqueId(), subject);
        if (reallyOnline && !hiddenFromViewer) {
            lore.add("<green>Online right now.");
        } else {
            Duration ago = Duration.between(Instant.ofEpochMilli(who.getLastLogin()), Instant.now());
            lore.add("<gray>Last seen <white>" + Times.describe(ago) + "</white> ago.");
        }
        if (RainsCore.isAvailable() && RainsCore.get().playtime().isKnown(subject)) {
            de.raindancer.core.social.presence.Playtime playtime = RainsCore.get().playtime();
            lore.add("<gray>Played <white>" + Times.describe(playtime.played(subject)) + "</white>.");
            lore.add("<gray>Active <white>" + Times.describe(playtime.playedActively(subject)) + "</white>, AFK <white>"
                    + Times.describe(playtime.playedAway(subject)) + "</white>.");
        }
        return lore;
    }
}
