package de.raindancer.core.ui.help;

import de.raindancer.core.social.desk.DeskOffer;
import de.raindancer.core.social.desk.ServiceDesk;
import de.raindancer.core.social.desk.ServiceDesks;
import de.raindancer.core.ui.menu.Icons;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.Optional;

/**
 * The guide standing in the world — a witch by the spawn, a person in any skin — whom players right-click to
 * start the same conversation {@code /help} starts.
 *
 * <p>A service desk of its own ({@link ServiceDesk#GUIDE}) with one offer, so talking to it opens the guide
 * directly; it is unhurtable and saved with its chunk like every attendant, and a mob placed as a guide stands
 * still: no AI, no despawning, no sound of its own.
 */
public final class HelpGuide implements DeskOffer {

    private final HelpDesk desk;

    public HelpGuide(HelpDesk desk) {
        this.desk = desk;
    }

    @Override
    public ItemStack icon(Player viewer) {
        return Icons.of(Material.ENCHANTED_BOOK, "<#C4B5FD>Ask " + desk.settings().guideName());
    }

    @Override
    public void use(Player viewer, Runnable backToDesk) {
        HelpDialogs.forReader(desk, viewer).home(true);
    }

    /**
     * A guide where the player stands, facing the way they face.
     *
     * @param type {@code mannequin} (or nothing) for a person in the guide's skins; otherwise any living mob
     * @return empty when {@code type} cannot stand as a guide
     */
    public Optional<Entity> place(Player player, String type) {
        Component name = Component.text(desk.settings().guideName(), NamedTextColor.LIGHT_PURPLE);
        String wanted = type == null ? "" : type.strip().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty() || wanted.equals("mannequin") || wanted.equals("person") || wanted.equals("player")) {
            return Optional.of(ServiceDesks.place(player, ServiceDesk.GUIDE, name));
        }
        EntityType entityType = EntityType.fromName(wanted.replace("minecraft:", ""));
        if (entityType == null || !entityType.isSpawnable() || entityType.getEntityClass() == null
                || !Mob.class.isAssignableFrom(entityType.getEntityClass())) {
            return Optional.empty();
        }
        Location at = player.getLocation().clone();
        at.setPitch(0);
        Entity placed = player.getWorld().spawnEntity(at, entityType, org.bukkit.event.entity.CreatureSpawnEvent
                .SpawnReason.CUSTOM, entity -> {
            LivingEntity guide = (LivingEntity) entity;
            guide.setAI(false);
            guide.setInvulnerable(true);
            guide.setSilent(true);
            guide.setPersistent(true);
            guide.setRemoveWhenFarAway(false);
            guide.setCollidable(false);
            guide.customName(name);
            guide.setCustomNameVisible(true);
            guide.getPersistentDataContainer().set(ServiceDesks.DESK, PersistentDataType.STRING, ServiceDesk.GUIDE.name());
        });
        return Optional.of(placed);
    }
}
