package de.raindancer.core.moderation.players;

import de.raindancer.core.ui.text.Text;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The one-line half of player management.
 *
 * <p>Every rule about what is allowed, what would kill somebody and what is already the case lives
 * in {@link PlayerAdmin} and is tested without a server. This is the seam.
 */
public final class BukkitPlayerAdminSink implements PlayerAdminSink {

    private static final LogChannel log = Log.of("players");
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Effect names that turned out not to exist. Complained about once each. */
    private final Set<String> unknown = ConcurrentHashMap.newKeySet();
    /** Whose scheduler a change is run through; null acts on the caller's thread, as before. */
    private final Plugin plugin;
    /** Core's shared flight, so the switch is {@code /fly}'s lease; null sets the switch directly, as before. */
    private final de.raindancer.core.world.movement.Flight flight;

    /** Acts on whichever thread calls it — right on Paper's main thread, wrong across Folia regions. */
    public BukkitPlayerAdminSink() {
        this(null);
    }

    /** Makes every change on the target's own thread, wherever the call came from. */
    public BukkitPlayerAdminSink(Plugin plugin) {
        this(plugin, null);
    }

    /**
     * Flight through Core's shared {@link de.raindancer.core.world.movement.Flight}: {@code /fly} takes a lease
     * instead of flipping the switch, so a claim that also lets them fly neither ends it nor is ended by it.
     */
    public BukkitPlayerAdminSink(Plugin plugin, de.raindancer.core.world.movement.Flight flight) {
        this.plugin = plugin;
        this.flight = flight;
    }

    /**
     * Runs {@code action} on the online player's own thread. A moderator's click arrives on their
     * region; the player it acts on may be ticking in another, and changing them from here races it.
     */
    private void onTarget(UUID who, Consumer<Player> action) {
        Player player = who == null ? null : Bukkit.getPlayer(who);
        if (player == null) {
            return;
        }
        if (plugin == null) {
            action.accept(player);
            return;
        }
        Scheduling.onOwner(plugin, player, () -> action.accept(player));
    }

    @Override
    public Optional<PlayerState> stateOf(UUID who) {
        Player player = Bukkit.getPlayer(who);
        if (player == null) {
            return Optional.empty();
        }
        // The attribute rather than a hardcoded twenty: a server with a plugin that raises maximum
        // health has players for whom twenty is not full, and a heal button that stops there is a
        // heal button that does not heal.
        double max = player.getAttribute(Attribute.MAX_HEALTH) == null
                ? 20 : player.getAttribute(Attribute.MAX_HEALTH).getValue();
        return Optional.of(new PlayerState(player.getHealth(), max, player.getFoodLevel(),
                flight == null ? player.getAllowFlight() : flight.hasOwnFlight(player), player.getGameMode().name()));
    }

    @Override
    public void health(UUID who, double health) {
        onTarget(who, player -> {
            double max = player.getAttribute(Attribute.MAX_HEALTH) == null
                    ? 20 : player.getAttribute(Attribute.MAX_HEALTH).getValue();
            // Clamped here as well as decided above: setHealth outside the range throws, and this
            // is the last place before it that can stop that.
            player.setHealth(Math.clamp(health, 0, max));
        });
    }

    @Override
    public void food(UUID who, int food) {
        onTarget(who, player -> player.setFoodLevel(Math.clamp(food, 0, 20)));
    }

    @Override
    public void effect(UUID who, String effect, int level, Duration lasting) {
        PotionEffectType type = effectType(effect);
        if (type == null) {
            return;
        }
        // Amplifier is level - 1: amplifier 0 is level I. Getting this backwards is what makes a
        // "Speed II" button apply Speed III, and it is the most common bug in this whole area.
        int amplifier = Math.max(0, level - 1);
        int ticks = lasting == null
                ? PotionEffect.INFINITE_DURATION : (int) Math.min(Integer.MAX_VALUE,
                        lasting.toMillis() / 50);
        onTarget(who, player ->
                player.addPotionEffect(new PotionEffect(type, ticks, amplifier, false, false, true)));
    }

    @Override
    public void clearEffect(UUID who, String effect) {
        PotionEffectType type = effectType(effect);
        if (type != null) {
            onTarget(who, player -> player.removePotionEffect(type));
        }
    }

    @Override
    public void clearAllEffects(UUID who) {
        onTarget(who, player -> player.getActivePotionEffects()
                .forEach(active -> player.removePotionEffect(active.getType())));
    }

    @Override
    public void allowFlight(UUID who, boolean allowed) {
        onTarget(who, player -> {
            if (flight != null) {
                if (allowed) {
                    flight.grant(player, de.raindancer.core.world.movement.Flight.FLY);
                    return;
                }
                flight.revoke(player, de.raindancer.core.world.movement.Flight.FLY);
                GameMode mode = player.getGameMode();
                boolean ownedByMode = mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
                if (flight.isGranted(player.getUniqueId()) || ownedByMode || !player.getAllowFlight()) {
                    return;
                }
            }
            player.setAllowFlight(allowed);
            if (!allowed && player.isFlying()) {
                player.setFlying(false);
            }
        });
    }

    @Override
    public void gamemode(UUID who, String mode) {
        GameMode wanted;
        try {
            wanted = GameMode.valueOf(mode);
        } catch (IllegalArgumentException | NullPointerException notAMode) {
            log.warn("There is no gamemode called '{}'.", mode);
            return;
        }
        onTarget(who, player -> player.setGameMode(wanted));
    }

    @Override
    public void kick(UUID who, String reason) {
        Component said = MINI.deserialize("<red>" + Text.literal(reason));
        onTarget(who, player -> player.kick(said));
    }

    @Override
    public void extinguish(UUID who) {
        onTarget(who, player -> player.setFireTicks(0));
    }

    /** An effect by name, from the registry so a new one works without this class changing. */
    private PotionEffectType effectType(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        PotionEffectType found = Registry.EFFECT.get(NamespacedKey.minecraft(key));
        if (found == null && unknown.add(key)) {
            log.warn("This server has no effect called '{}'.", key);
        }
        return found;
    }
}
