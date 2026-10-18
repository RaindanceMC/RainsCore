package de.raindancer.core.moderation.whitelist;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

@Settings(id = "core-whitelist", topics = {
        @Topic(path = "moderation/whitelist", title = "Whitelist", icon = Material.PAPER),
})
public record WhitelistSettings(

        @In("moderation/whitelist") @Title("Share the network's whitelist")
        @Describe("On: this server's whitelist is the network's — /whitelist add or remove here changes it "
                + "everywhere that shares it. Off: this server keeps a whitelist of its own.")
        @Key("whitelist.network")
        boolean shared) {

    public static final WhitelistSettings DEFAULTS = new WhitelistSettings(true);
}
