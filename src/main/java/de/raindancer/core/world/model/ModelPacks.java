package de.raindancer.core.world.model;

import de.raindancer.core.content.pack.PackContribution;
import de.raindancer.core.content.pack.ResourcePacks;
import de.raindancer.core.platform.util.Scheduling;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.BetterModelPlatform;
import kr.toxicity.model.api.pack.PackByte;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The model engine's pack inside Core's: every finished build of BetterModel's pack becomes a contribution to the
 * server's one pack, so players get one download. Only loaded while BetterModel runs. Only the base assets are
 * taken — the overlays are for older clients than this server allows.
 */
final class ModelPacks {

    static final String OWNER = "BetterModel";
    static final String NAME = "models";
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("RainsCore");

    private ModelPacks() {
    }

    static void start(Plugin core, ResourcePacks packs, Path folder, Path engineZip) {
        Consumer<BetterModelPlatform.ReloadResult> taken = result -> {
            if (result instanceof BetterModelPlatform.ReloadResult.Success success) {
                Map<String, byte[]> assets = new LinkedHashMap<>();
                for (PackByte file : success.packResult().assets()) {
                    assets.put(file.path().path(), file.bytes());
                }
                take(core, packs, folder, assets);
            }
        };
        BetterModel.platform().addReloadEndHandler(taken);
        // A build that finished before Core was listening is on disk already.
        Scheduling.async(core, () -> {
            if (Files.isRegularFile(engineZip)) {
                try {
                    take(core, packs, folder, PackFolder.readZip(engineZip));
                } catch (IOException failed) {
                    LOG.warning("BetterModel's pack could not be read from " + engineZip + ": " + failed.getMessage());
                }
            }
        });
    }

    private static void take(Plugin core, ResourcePacks packs, Path folder, Map<String, byte[]> files) {
        files.keySet().removeIf(name -> name.equals("pack.mcmeta") || name.equals("pack.png"));
        try {
            PackFolder.write(files, folder);
        } catch (IOException failed) {
            LOG.warning("BetterModel's pack could not be written to " + folder + ": " + failed.getMessage());
            return;
        }
        packs.contribute(PackContribution.of(OWNER, NAME, folder).describedAs("Custom models (BetterModel)"));
        if (packs.current().isPresent()) {
            packs.rebuild().ifPresent(built -> packs.sendToAll(
                    core.getServer().getOnlinePlayers().stream().map(Player::getUniqueId).toList()));
        }
    }
}
