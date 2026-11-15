package de.raindancer.core.data.settings;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.util.Scheduling;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.List;

/**
 * Saving after a change, and telling whoever made it when the file would not take it.
 *
 * <p>Off the thread the change arrived on, because it writes a YAML file per plugin with settings and
 * doing that on a region thread stalls the world for the disk. A file the owner broke by hand is left as
 * it is; the change is in effect, and the admin is told it lasts only until a restart and what to fix.
 */
final class SettingsSaving {

    private SettingsSaving() {
    }

    static void saveThenTell(SettingsRegistry registry, CommandSender who) {
        Runnable save = () -> {
            List<Path> unwritten = registry.saveAllChecked();
            if (who != null) {
                for (Path file : unwritten) {
                    who.sendMessage(notSaved(file));
                }
            }
        };
        Plugin core = Bukkit.getServer() == null ? null : Bukkit.getPluginManager().getPlugin("RainsCore");
        if (core == null) {
            save.run();
        } else {
            Scheduling.async(core, save);
        }
    }

    private static Component notSaved(Path file) {
        Object shown = file.getParent() == null || file.getParent().getFileName() == null
                ? file.getFileName() : file.getParent().getFileName().resolve(file.getFileName());
        if (RainsCore.isAvailable()) {
            return RainsCore.get().messages().prefixed("settings.not-saved", "file", String.valueOf(shown));
        }
        return Component.text("Changed for now, but " + shown + " could not be saved. Fix the mistake in it "
                + "(the console says where); until then a restart undoes this.");
    }
}
