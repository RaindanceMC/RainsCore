package de.raindancer.core.content.pack;

import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * A plugin's own resource pack files shipped inside its jar — models, textures, item definitions — copied out and
 * offered to Core's {@link ResourcePacks}, which builds and sends the pack. A server that already sends a pack builds
 * it again right away, so nobody waits for a restart to see the new looks.
 *
 * <pre>{@code
 * JarPack.offer(plugin, core.resourcePacks(), MyPack.class, "/my/module/pack/", FILES,
 *         "Economy", "items", "The Auto Sell Chest and the Sell Wand");
 * }</pre>
 */
public final class JarPack {

    private JarPack() {
    }

    /**
     * Copies {@code files}, each a path below {@code root} in the jar of {@code anchor}, to the same paths below
     * {@code into}.
     *
     * @throws IOException naming the first file the jar does not have, or what the disk refused
     */
    public static void copy(Class<?> anchor, String root, List<String> files, Path into) throws IOException {
        for (String file : files) {
            Path target = into.resolve(file);
            Files.createDirectories(target.getParent());
            try (InputStream in = anchor.getResourceAsStream(root + file)) {
                if (in == null) {
                    throw new IOException(file + " is missing from the jar");
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Copies the files to {@code plugins/<plugin>/pack/<name>} and offers them as {@code owner}'s contribution
     * {@code name}; withdraw it again with {@code packs.withdraw(owner.toLowerCase() + ":" + name)}.
     *
     * @throws IOException when the files could not be written — the plugin works on, without its looks
     */
    public static void offer(Plugin plugin, ResourcePacks packs, Class<?> anchor, String root, List<String> files,
                             String owner, String name, String description) throws IOException {
        Path folder = plugin.getDataFolder().toPath().resolve("pack").resolve(name);
        copy(anchor, root, files, folder);
        packs.contribute(PackContribution.of(owner, name, folder).describedAs(description));
        if (packs.current().isPresent()) {
            Scheduling.async(plugin, () -> packs.rebuild().ifPresent(built -> packs.sendToAll(
                    plugin.getServer().getOnlinePlayers().stream().map(Player::getUniqueId).toList())));
        }
    }
}
