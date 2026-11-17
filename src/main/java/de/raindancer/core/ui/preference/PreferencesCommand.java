package de.raindancer.core.ui.preference;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Opens {@link PreferencesDialog} for the player who typed it. */
public final class PreferencesCommand implements BasicCommand {

    @Override
    public boolean canUse(CommandSender sender) {
        return true;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (source.getSender() instanceof Player player) {
            PreferencesDialog.open(player);
        } else {
            source.getSender().sendMessage(Component.text("Only players have preferences."));
        }
    }
}
