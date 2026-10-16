package de.raindancer.core.social.desk;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Talking to an attendant opens their desk; nobody hurts them. */
public final class DeskListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onTalk(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ServiceDesks.deskOf(event.getRightClicked()).ifPresent(desk -> {
            event.setCancelled(true);
            DeskMenu.talk(event.getPlayer(), desk);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTalkAt(PlayerInteractAtEntityEvent event) {
        if (ServiceDesks.deskOf(event.getRightClicked()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (ServiceDesks.deskOf(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }
}
