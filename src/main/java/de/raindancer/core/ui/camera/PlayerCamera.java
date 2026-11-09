package de.raindancer.core.ui.camera;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets one player see the world through another entity — a cutscene, a showcase, an AFK camera — while they stay
 * exactly where and what they are: in their own game mode, picking up items, keeping farms and mob spawns running.
 * Spectator mode with {@link Player#setSpectatorTarget} would do the same look and switch all of that off.
 *
 * <p>Only the viewer's client is told (the game's own camera packet); the server still has the player at their own
 * eyes. While looking through something else the client does not walk, but still sends its keys, so a
 * {@code PlayerInputEvent} is the moment to {@link #reset} them. The entity has to be tracked by the viewer — near
 * them, shown to them — and the viewer reset before it is removed, or the client keeps looking through nothing.
 *
 * <p>The vanilla client still draws its own arm and HUD over a borrowed camera. {@link #pretendSpectator} hides
 * them the way a spectator's screen has none, by telling only that client it is a spectator — the server, every
 * other player and the anti-cheat's view of the server keep the real game mode. While the client believes it, it has
 * no physics of its own (no gravity, nothing pushes it), so only pretend for somebody standing still on the ground,
 * and {@link #showRealMode} before they move again.
 *
 * <p>Reflective, since Core compiles against the API only; on a server it does not fit, {@link #works} is false and
 * nothing is sent.
 */
public final class PlayerCamera {

    private static final LogChannel log = Log.of("camera");
    private static volatile PlayerCamera shared;

    private final Constructor<?> newPacket;
    private final Class<?> packetType;
    private final Constructor<?> newGameEvent;
    private final Object changeGameMode;
    private final Map<Class<?>, Method> senders = new ConcurrentHashMap<>();

    public PlayerCamera(ClassLoader server) {
        Constructor<?> resolved = null;
        Class<?> packet = null;
        try {
            Class<?> entity = Class.forName("net.minecraft.world.entity.Entity", false, server);
            packet = Class.forName("net.minecraft.network.protocol.Packet", false, server);
            resolved = Class.forName("net.minecraft.network.protocol.game.ClientboundSetCameraPacket", false, server)
                    .getConstructor(entity);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError misfit) {
            log.warn("The camera packet does not fit this server version ({}); nobody's camera moves.",
                    misfit.toString());
        }
        this.newPacket = resolved;
        this.packetType = packet;
        Constructor<?> gameEvent = null;
        Object change = null;
        try {
            Class<?> events = Class.forName("net.minecraft.network.protocol.game.ClientboundGameEventPacket", false,
                    server);
            Class<?> type = Class.forName("net.minecraft.network.protocol.game.ClientboundGameEventPacket$Type",
                    false, server);
            gameEvent = events.getConstructor(type, float.class);
            change = events.getField("CHANGE_GAME_MODE").get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError misfit) {
            gameEvent = null;
            log.warn("The game mode packet does not fit this server version ({}); borrowed cameras keep the HUD.",
                    misfit.toString());
        }
        this.newGameEvent = packet == null ? null : gameEvent;
        this.changeGameMode = change;
    }

    /** The one for this server, resolved against its own classes the first time it is asked for. */
    public static PlayerCamera of(Server server) {
        PlayerCamera known = shared;
        if (known == null) {
            synchronized (PlayerCamera.class) {
                known = shared;
                if (known == null) {
                    known = new PlayerCamera(server.getClass().getClassLoader());
                    shared = known;
                }
            }
        }
        return known;
    }

    public boolean works() {
        return newPacket != null;
    }

    /** Whether {@link #pretendSpectator} can work here. */
    public boolean hidesHud() {
        return newGameEvent != null;
    }

    /** Only {@code viewer}'s client believes it is a spectator: no arm, no hotbar, no hearts. @return whether sent */
    public boolean pretendSpectator(Player viewer) {
        return sendMode(viewer, number(GameMode.SPECTATOR), false);
    }

    /** Tells {@code viewer}'s client its real game mode and abilities again. @return whether it was sent */
    public boolean showRealMode(Player viewer) {
        return sendMode(viewer, number(viewer.getGameMode()), true);
    }

    static int number(GameMode mode) {
        return switch (mode) {
            case SURVIVAL -> 0;
            case CREATIVE -> 1;
            case ADVENTURE -> 2;
            case SPECTATOR -> 3;
        };
    }

    /** {@code abilities}: the server sends the player's real abilities after it, since a mode resets the client's. */
    boolean sendMode(Object viewer, float mode, boolean abilities) {
        if (newGameEvent == null || viewer == null) {
            return false;
        }
        try {
            Object player = handle(viewer);
            Object connection = player.getClass().getField("connection").get(player);
            senderOf(connection.getClass()).invoke(connection, newGameEvent.newInstance(changeGameMode, mode));
            if (abilities) {
                player.getClass().getMethod("onUpdateAbilities").invoke(player);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException misfit) {
            log.debug("No game mode packet for {}: {}", viewer, misfit.toString());
            return false;
        }
    }

    /** {@code viewer} sees through {@code through} from now on. @return whether it was sent */
    public boolean look(Player viewer, Entity through) {
        return send(viewer, through);
    }

    /** {@code viewer} sees through their own eyes again. @return whether it was sent */
    public boolean reset(Player viewer) {
        return send(viewer, viewer);
    }

    /** Both are Bukkit's wrappers, or anything with a {@code getHandle()}. Never throws. */
    boolean send(Object viewer, Object through) {
        if (newPacket == null || viewer == null || through == null) {
            return false;
        }
        try {
            Object player = handle(viewer);
            Object packet = newPacket.newInstance(handle(through));
            Object connection = player.getClass().getField("connection").get(player);
            senderOf(connection.getClass()).invoke(connection, packet);
            return true;
        } catch (ReflectiveOperationException | RuntimeException misfit) {
            log.debug("No camera packet for {}: {}", viewer, misfit.toString());
            return false;
        }
    }

    private static Object handle(Object wrapper) throws ReflectiveOperationException {
        return wrapper.getClass().getMethod("getHandle").invoke(wrapper);
    }

    private Method senderOf(Class<?> listener) throws NoSuchMethodException {
        Method known = senders.get(listener);
        if (known != null) {
            return known;
        }
        for (Method method : listener.getMethods()) {
            if (method.getName().equals("send") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(packetType)) {
                senders.put(listener, method);
                return method;
            }
        }
        throw new NoSuchMethodException(listener.getName() + ".send(Packet)");
    }
}
