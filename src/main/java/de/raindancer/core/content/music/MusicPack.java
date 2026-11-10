package de.raindancer.core.content.music;

import de.raindancer.core.content.pack.PackContribution;
import de.raindancer.core.content.pack.ResourcePacks;
import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * A plugin's music folder ({@code plugins/<plugin>/music}) offered to the server's resource pack, as
 * {@link MusicFolder} builds it. Rebuilds and resends the pack when one was already out, so players online now get
 * the music too.
 */
public final class MusicPack {

    private final ResourcePacks packs;
    private final String owner;
    private final List<Track> tracks;

    private MusicPack(ResourcePacks packs, String owner, List<Track> tracks) {
        this.packs = packs;
        this.owner = owner;
        this.tracks = tracks;
    }

    /**
     * @param owner     the plugin as a human writes it, the contribution's owner
     * @param namespace its resource-pack namespace
     * @throws IOException when the folder could not be read or the pack written
     */
    public static MusicPack offer(Plugin plugin, ResourcePacks packs, String owner, String namespace, String label)
            throws IOException {
        Path data = plugin.getDataFolder().toPath();
        Path pack = data.resolve("pack").resolve("music");
        List<Track> tracks = MusicFolder.build(data.resolve("music"), pack, namespace, label);
        if (!tracks.isEmpty()) {
            packs.contribute(PackContribution.of(owner, "music", pack).describedAs(label));
            if (packs.current().isPresent()) {
                Scheduling.async(plugin, () -> packs.rebuild().ifPresent(built -> packs.sendToAll(
                        plugin.getServer().getOnlinePlayers().stream().map(Player::getUniqueId).toList())));
            }
        }
        return new MusicPack(packs, owner, tracks);
    }

    public List<Track> tracks() {
        return tracks;
    }

    /** Takes the music back out of the server's pack — on disable. */
    public void withdraw() {
        if (!tracks.isEmpty()) {
            packs.withdraw(owner.toLowerCase(Locale.ROOT) + ":music");
        }
    }
}
