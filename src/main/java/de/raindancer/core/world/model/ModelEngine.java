package de.raindancer.core.world.model;

import de.raindancer.core.content.pack.ResourcePacks;
import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.plugin.Plugin;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Custom models for every Rain plugin, through the engine Core brings along (BundledEngine): Core installs it, and
 * while it runs its pack is part of Core's. A plugin asks {@link #available()} before it shows a model.
 */
public final class ModelEngine {

    private static final String PLUGIN = "BetterModel";

    private ModelEngine() {
    }

    /** Whether models can be shown now: the engine is installed and running. */
    public static boolean available() {
        var engine = org.bukkit.Bukkit.getPluginManager().getPlugin(PLUGIN);
        return engine != null && engine.isEnabled();
    }

    /** Core's start: the engine put into plugins/ for the next start if needed, and its pack taken in if it runs. */
    public static void start(Plugin core, ResourcePacks packs, boolean install, Logger log) {
        Path plugins = core.getDataFolder().toPath().getParent();
        if (install) {
            Scheduling.async(core, () -> {
                try {
                    BundledEngine.Action done = BundledEngine.install(plugins,
                            core.getDataFolder().toPath().resolve("replaced"));
                    if (done != BundledEngine.Action.NONE) {
                        log.info("Model engine BetterModel " + BundledEngine.VERSION + " "
                                + (done == BundledEngine.Action.INSTALL ? "installed" : "updated")
                                + "; it starts with the next restart.");
                    }
                } catch (UncheckedIOException failed) {
                    log.warning("Model engine could not be installed: " + failed.getMessage());
                }
            });
        }
        if (available()) {
            try {
                // BetterModel writes its pack next to its own folder: plugins/BetterModel/build.zip by default.
                ModelPacks.start(core, packs, core.getDataFolder().toPath().resolve("packs").resolve("bettermodel"),
                        plugins.resolve(PLUGIN).resolve("build.zip"));
            } catch (LinkageError olderEngine) {
                log.warning("BetterModel runs, but not a version Core understands; custom models stay off.");
            }
        }
    }
}
