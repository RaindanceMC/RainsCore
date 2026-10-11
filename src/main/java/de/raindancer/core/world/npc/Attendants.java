package de.raindancer.core.world.npc;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * People standing in the world to serve players: a casino dealer, an insurance agent. Player-shaped, still,
 * unhurtable, saved with the chunk — whoever places one tags it, and whoever listens for right clicks reads
 * the tag back. A placed attendant needs no file of its own.
 */
public final class Attendants {

    /** A made-up id for the suit's profile, the same every time (the casino dealers' since economy 0.13). */
    public static final UUID SUIT_ID = UUID.nameUUIDFromBytes("rainseconomy:dealer".getBytes(StandardCharsets.UTF_8));

    /**
     * A black suit with a red tie. Mojang-signed (MineSkin "Tuxedo", texture 53c1e278…), so every client
     * shows it without asking anybody.
     */
    public static final String SUIT_VALUE = "ewogICJ0aW1lc3RhbXAiIDogMTcwMTE5MTAzMjkxMSwKICAicHJvZmlsZUlkIiA6ICJjYjYxY2U5ODc4ZWI0NDljODA5MzliNWYx"
            + "NTkwMzE1MiIsCiAgInByb2ZpbGVOYW1lIiA6ICJWb2lkZWRUcmFzaDUxODUiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVl"
            + "LAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQu"
            + "bmV0L3RleHR1cmUvNTNjMWUyNzgwMGFhYTJhYzI1ZjMyMzk5NjU3ZjVlMjQwNThlZjQzNjI3MjFjMjA2YTNhNTU4MTQzYmUyYzdj"
            + "NCIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9";

    public static final String SUIT_SIGNATURE = "RzQDM/CjiOeFyhEnq7f0+Hzra3YAseTHfPGL0B1BqcTEZO5cvuoweI0LyeD/jfhShPVZem36/inTJP+FYTGI3udDwpeGTXu9YzCE"
            + "nWtb8SfQtksyDRuuhdZPZSoZYU4POtTL+hgtvywOtF5c3WpFscJhm5+Cr6rQDqNq7qaUY7F1qMq8WMCp+9wU9+VS5E/jWxMPIDz0"
            + "gQa3rwbJzodpvWRDwlS93VFxzXBQqENboUuISp+DrLDwWkYrwGog3TmEqVa9n6T8C/FquzC46ojcMYkXTZPgyHpDHPWxmGiMphhl"
            + "/QPK2XmngJQET4qVJRuvnWdPVBWuWB/LJbX0FQrRx4LLcYzkOls838bfBflGEKmdeTK71TEo5c8udSrz1PU1wG4zYnfmwYN+JoBW"
            + "PrNg+QC2XaEQGuGIDHPkaLQeYoSCM//WAuu6qi53EM6INeQ/PZgRwU9+1UWx1G62AHnmJt/iUWP9eAyT5t0LVrum8BhiGKQeq6cG"
            + "yB2JqCzpHZFytjusBkdmCtiE+T2T90zyjRb6YdzFF7M5QzEpj6KDUk06+o19H6ggGCpQRZ3oJ5c9zSirq9aFmXGkGMRXxDBoHtiA"
            + "iOn1d2mMz+JUxSdJYd2Y/VDWDtt77Hqh/1PmID5D3EPJoIdvL1Y2rXWBcYQSUScmA6ikgk0jwf9iEa6b/eo=";

    /** How far a player may stand from an attendant and still count as being there. */
    public static final double REACH = 6;

    private Attendants() {
    }

    /** The suit, or the skin of the player named — a name the server looks up with Mojang. */
    public static ResolvableProfile look(String skinOf) {
        ResolvableProfile.Builder profile = ResolvableProfile.resolvableProfile();
        if (skinOf != null && !skinOf.isBlank()) {
            return profile.name(skinOf.strip()).build();
        }
        // Id, name and skin all given, the profile is complete — otherwise the server asks Mojang who this is.
        return profile.uuid(SUIT_ID).name("Attendant")
                .addProperty(new ProfileProperty("textures", SUIT_VALUE, SUIT_SIGNATURE)).build();
    }

    /**
     * An attendant where the player stands, facing them. On the player's own region thread.
     *
     * @param tag marks it as whatever it is (a PDC entry), before it is added to the world
     */
    public static Mannequin place(Player player, Component name, Component description, ResolvableProfile look,
                                  Consumer<Mannequin> tag) {
        Location at = player.getLocation();
        Location facing = at.clone();
        facing.setYaw(at.getYaw() + 180);
        facing.setPitch(0);
        return at.getWorld().spawn(facing, Mannequin.class, attendant -> {
            attendant.setImmovable(true);
            attendant.setInvulnerable(true);
            attendant.setSilent(true);
            attendant.setPersistent(true);
            attendant.setRemoveWhenFarAway(false);
            attendant.customName(name);
            attendant.setCustomNameVisible(true);
            attendant.setDescription(description);
            attendant.setProfile(look);
            tag.accept(attendant);
        });
    }

    /** The nearest attendant within {@code radius} blocks that {@code which} accepts. */
    public static Optional<Entity> nearest(Player player, double radius, Predicate<Entity> which) {
        Entity nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity nearby : player.getNearbyEntities(radius, radius, radius)) {
            if (nearby instanceof Mannequin && which.test(nearby)) {
                double distance = nearby.getLocation().distanceSquared(player.getLocation());
                if (distance <= radius * radius && distance < best) {
                    best = distance;
                    nearest = nearby;
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    /** Whether such an attendant is within reach of the player. */
    public static boolean near(Player player, Predicate<Entity> which) {
        return nearest(player, REACH, which).isPresent();
    }
}
