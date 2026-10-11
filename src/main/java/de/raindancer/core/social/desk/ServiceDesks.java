package de.raindancer.core.social.desk;

import de.raindancer.core.platform.util.PluginCode;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.npc.Attendants;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service desks: attendants standing in the world (an insurance agent) and what plugins offer at them. With
 * a desk's "in person only" setting on, a plugin offers that service at the desk and nowhere else — its
 * command and its other screens send the player to an attendant ({@link #refuseRemote}).
 */
public final class ServiceDesks {

    public static final NamespacedKey DESK = Objects.requireNonNull(NamespacedKey.fromString("rainscore:desk"));

    private record Offered(ServiceDesk desk, DeskOffer offer) {
    }

    private static final List<Offered> offers = new CopyOnWriteArrayList<>();
    private static final Set<ServiceDesk> inPerson = EnumSet.noneOf(ServiceDesk.class);
    private static volatile Messages messages;
    private static volatile Brand brand;

    private ServiceDesks() {
    }

    /** Core, at start: who speaks for the desks. */
    public static void install(Messages coreMessages, Brand coreBrand) {
        messages = coreMessages;
        brand = coreBrand;
    }

    static Brand brand() {
        return brand;
    }

    static Messages messages() {
        return messages;
    }

    /** Offering the same thing twice is one offer. */
    public static synchronized void offer(ServiceDesk desk, DeskOffer offer) {
        if (desk != null && offer != null && offers.stream().noneMatch(each -> each.offer() == offer)) {
            offers.add(new Offered(desk, offer));
        }
    }

    public static synchronized void withdraw(DeskOffer offer) {
        offers.removeIf(each -> each.offer() == offer);
    }

    /** Everything offered at this desk, in the order it was offered. */
    public static List<DeskOffer> offers(ServiceDesk desk) {
        return offers.stream().filter(each -> each.desk() == desk).map(Offered::offer).toList();
    }

    public static synchronized int forgetFrom(ClassLoader loader) {
        int before = offers.size();
        offers.removeIf(each -> PluginCode.isFrom(each.offer(), loader));
        return before - offers.size();
    }

    /** Whether this desk's services are only to be had from one of its attendants. */
    public static boolean inPersonOnly(ServiceDesk desk) {
        synchronized (inPerson) {
            return inPerson.contains(desk);
        }
    }

    /** Core's settings decide it. */
    public static void inPersonOnly(ServiceDesk desk, boolean only) {
        synchronized (inPerson) {
            if (only) {
                inPerson.add(desk);
            } else {
                inPerson.remove(desk);
            }
        }
    }

    /**
     * For a command or screen that offers a desk's service somewhere else: with "in person only" on, tells the
     * player to see an attendant and answers true — the caller then stops.
     */
    public static boolean refuseRemote(Player player, ServiceDesk desk) {
        if (!inPersonOnly(desk)) {
            return false;
        }
        Messages speaking = messages;
        if (speaking != null) {
            speaking.send(player, "desk.in-person-only", "desk", desk.title());
        }
        return true;
    }

    /** An attendant of this desk where the player stands, facing them. On the player's own region thread. */
    public static Mannequin place(Player player, ServiceDesk desk) {
        return Attendants.place(player, Component.text(desk.title(), NamedTextColor.GOLD),
                Component.text("Right click to talk", NamedTextColor.GRAY), Attendants.look(null),
                attendant -> attendant.getPersistentDataContainer().set(DESK, PersistentDataType.STRING, desk.name()));
    }

    public static Optional<ServiceDesk> deskOf(Entity entity) {
        if (!(entity instanceof Mannequin)) {
            return Optional.empty();
        }
        return ServiceDesk.read(entity.getPersistentDataContainer().get(DESK, PersistentDataType.STRING));
    }

    /** Takes away the nearest attendant of any desk within a few blocks; whether there was one. */
    public static boolean removeNearest(Player player) {
        Optional<Entity> nearest = Attendants.nearest(player, 4, entity -> deskOf(entity).isPresent());
        nearest.ifPresent(Entity::remove);
        return nearest.isPresent();
    }

    /** Whether an attendant of this desk is within reach. */
    public static boolean near(Player player, ServiceDesk desk) {
        return Attendants.near(player, entity -> deskOf(entity).filter(desk::equals).isPresent());
    }

    /** Forgets every offer and switch — for Core shutting down, and for tests. */
    public static synchronized void clear() {
        offers.clear();
        synchronized (inPerson) {
            inPerson.clear();
        }
    }
}
