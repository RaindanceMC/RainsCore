package de.raindancer.core.world.movement;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/** How fast a ridden horse goes, on a road and off it. Shipped at 100 percent both: vanilla. */
@Settings(id = "core-mounts", topics = {
        @Topic(path = "world/mounts", title = "Horses on roads", icon = Material.SADDLE),
})
public record MountSettings(

        @In("world/mounts") @Title("Speed on a road (percent)") @Range(min = 10, max = 400)
        @Describe("A ridden horse, donkey, mule or camel standing on one of the road blocks below goes this much of its "
                + "own speed, so a faster horse stays faster. 100 is vanilla; an average horse does about 9.7 blocks a "
                + "second there, the best about 14.6.")
        @Key("mounts.road-percent")
        int roadPercent,

        @In("world/mounts") @Title("Speed everywhere else (percent)") @Range(min = 10, max = 400)
        @Describe("The same off the road. 100 is vanilla.")
        @Key("mounts.off-road-percent")
        int offRoadPercent,

        @In("world/mounts") @Title("Road blocks")
        @Describe("Block names, comma separated, that count as road — what the hooves stand on. Dirt path is what a "
                + "shovel makes of grass.")
        @Key("mounts.road-blocks")
        String roadBlocks) {

    public static final MountSettings DEFAULTS = new MountSettings(100, 100, "dirt_path, gravel");
}
