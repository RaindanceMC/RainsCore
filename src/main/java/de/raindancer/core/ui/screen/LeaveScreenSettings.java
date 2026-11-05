package de.raindancer.core.ui.screen;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

@Settings(id = "core-leave-screen", topics = {
        @Topic(path = "appearance/screens", title = "Join and kick screens", icon = Material.OAK_DOOR),
})
public record LeaveScreenSettings(

        @In("appearance/screens") @Title("Name on top")
        @Describe("Shown in capitals in the tag gradient above every join refusal and kick (whitelist code, ban, "
                + "maintenance, anticheat). Plain text. Empty: the server name. On a network, give every server the "
                + "same one.")
        @Key("screens.title")
        String title,

        @In("appearance/screens") @Title("Footer")
        @Describe("A small grey line at the bottom of those screens, e.g. your Discord invite or address. Empty: none.")
        @Key("screens.footer")
        String footer) {

    public static final LeaveScreenSettings DEFAULTS = new LeaveScreenSettings("", "");
}
