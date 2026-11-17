package de.raindancer.core.ui.travel;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Opens {@link TravelDialog} for the player who typed it. */
public final class TravelCommand implements BasicCommand {

    @Override
    public boolean canUse(CommandSender sender) {
        return true;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (source.getSender() instanceof Player player) {
            TravelDialog.open(player);
        } else {
            source.getSender().sendMessage(Component.text("Only players travel."));
        }
    }
}
