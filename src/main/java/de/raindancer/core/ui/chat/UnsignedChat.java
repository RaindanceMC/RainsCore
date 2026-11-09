package de.raindancer.core.ui.chat;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Chat nobody can report to Mojang, and no "Chat messages can't be verified" warning (what FreedomChat does):
 * the client's chat key never reaches the server, so every message is unsigned; player chat goes out as system
 * lines; and the login and the server list tell the client the server enforces secure chat, which is what makes
 * it show no warning. The server keeps {@code enforce-secure-profile=false}, so Bedrock players and clients
 * without a key chat as before.
 *
 * <p>Reflective, since Core compiles against the API only; on a server it does not fit, it stands down.
 */
public final class UnsignedChat {

    private static final LogChannel log = Log.of("chat");
    static final String HANDLER = "rains_unsigned_chat";
    static final String INBOUND = "rains_unsigned_chat_in";
    private static final String GAME = "net.minecraft.network.protocol.game.";
    private static final String STATUS = "net.minecraft.network.protocol.status.";

    private final BooleanSupplier on;
    private final Types types;

    private record Types(Class<?> login, Class<?> status, Class<?> statusPacket, Constructor<?> newStatusPacket,
                         Method statusOf, Class<?> playerChat, Method body, Method bodyContent, Method unsigned,
                         Method chatType, Method decorate, Method literal, Constructor<?> newSystemChat,
                         Class<?> chatSession) {
    }

    public UnsignedChat(ClassLoader server, BooleanSupplier on) {
        this.on = on;
        Types resolved;
        try {
            resolved = resolve(server);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError misfit) {
            log.warn("Unsigned chat does not fit this server version ({}); chat stays as it is.", misfit.toString());
            resolved = null;
        }
        this.types = resolved;
    }

    public boolean works() {
        return types != null;
    }

    private static Types resolve(ClassLoader server) throws ReflectiveOperationException {
        Class<?> component = Class.forName("net.minecraft.network.chat.Component", false, server);
        Class<?> playerChat = Class.forName(GAME + "ClientboundPlayerChatPacket", false, server);
        Class<?> systemChat = Class.forName(GAME + "ClientboundSystemChatPacket", false, server);
        Class<?> statusPacket = Class.forName(STATUS + "ClientboundStatusResponsePacket", false, server);
        Class<?> status = Class.forName(STATUS + "ServerStatus", false, server);
        Method body = playerChat.getMethod("body");
        Method chatType = playerChat.getMethod("chatType");
        Types types = new Types(
                Class.forName(GAME + "ClientboundLoginPacket", false, server),
                status, statusPacket, statusPacket.getConstructor(status), statusPacket.getMethod("status"),
                playerChat, body, body.getReturnType().getMethod("content"), playerChat.getMethod("unsignedContent"),
                chatType, chatType.getReturnType().getMethod("decorate", component),
                component.getMethod("literal", String.class),
                systemChat.getConstructor(component, boolean.class),
                Class.forName(GAME + "ServerboundChatSessionUpdatePacket", false, server));
        canonical(types.login());
        canonical(status);
        return types;
    }

    /** The packet as it should leave: the same object when nothing changes. Never throws. */
    public Object outbound(Object packet) {
        Types t = types;
        if (t == null || packet == null || !on.getAsBoolean()) {
            return packet;
        }
        try {
            Class<?> type = packet.getClass();
            if (type == t.login()) {
                return with(packet, "enforcesSecureChat", true);
            }
            if (type == t.playerChat()) {
                Optional<?> unsigned = (Optional<?>) t.unsigned().invoke(packet);
                Object content = unsigned.isPresent() ? unsigned.get()
                        : t.literal().invoke(null, t.bodyContent().invoke(t.body().invoke(packet)));
                Object decorated = t.decorate().invoke(t.chatType().invoke(packet), content);
                return t.newSystemChat().newInstance(decorated, false);
            }
            if (type == t.statusPacket()) {
                Object status = t.statusOf().invoke(packet);
                Object claimed = with(status, "enforcesSecureChat", true);
                return claimed == status ? packet : t.newStatusPacket().newInstance(claimed);
            }
        } catch (ReflectiveOperationException | RuntimeException unexpected) {
            log.debug("A packet went out as it was: {}", unexpected.toString());
        }
        return packet;
    }

    /** Whether the server should never see this packet: the client's chat key. */
    public boolean dropInbound(Object packet) {
        Types t = types;
        return t != null && packet != null && packet.getClass() == t.chatSession() && on.getAsBoolean();
    }

    /** A copy of a record with one component set, or the record itself when it already has that value. */
    static Object with(Object record, String name, Object value) throws ReflectiveOperationException {
        RecordComponent[] parts = record.getClass().getRecordComponents();
        Object[] values = new Object[parts.length];
        boolean found = false;
        for (int i = 0; i < parts.length; i++) {
            values[i] = parts[i].getAccessor().invoke(record);
            if (parts[i].getName().equals(name)) {
                if (value.equals(values[i])) {
                    return record;
                }
                values[i] = value;
                found = true;
            }
        }
        if (!found) {
            throw new NoSuchFieldException(record.getClass().getName() + "." + name);
        }
        return canonical(record.getClass()).newInstance(values);
    }

    private static Constructor<?> canonical(Class<?> record) throws NoSuchMethodException {
        RecordComponent[] parts = record.getRecordComponents();
        if (parts == null) {
            throw new NoSuchMethodException(record.getName() + " is not a record");
        }
        Class<?>[] types = new Class<?>[parts.length];
        for (int i = 0; i < parts.length; i++) {
            types[i] = parts[i].getType();
        }
        return record.getDeclaredConstructor(types);
    }

    // ---------------------------------------------------------------------------- on the server

    private static final String PACKET_HANDLER = "packet_handler";
    private Object listenerKey;

    /**
     * Sits in every connection from its first packet on, since the login packet goes out before any join event.
     * Writing, it is last in the pipeline, so Core's other packet hooks see the system lines it makes; reading, it
     * is before the server's own handler, which would otherwise take the chat key first.
     */
    public void install(ClassLoader server, Iterable<? extends Player> online) {
        if (types == null) {
            return;
        }
        try {
            Class<?> holder = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder", true, server);
            Class<?> listener = Class.forName("io.papermc.paper.network.ChannelInitializeListener", true, server);
            Object key = net.kyori.adventure.key.Key.key("rainscore", "unsigned_chat");
            Object hook = java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                    (proxy, method, args) -> {
                        if (method.getName().equals("afterInitChannel")) {
                            add((Channel) args[0]);
                            return null;
                        }
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> "RainsCore unsigned chat";
                        };
                    });
            holder.getMethod("addListener", net.kyori.adventure.key.Key.class, listener).invoke(null, key, hook);
            listenerKey = key;
        } catch (ReflectiveOperationException | RuntimeException misfit) {
            log.warn("Unsigned chat could not hook new connections ({}); chat stays as it is.", misfit.toString());
            return;
        }
        for (Player player : online) {
            try {
                Channel channel = channelOf(player);
                channel.eventLoop().execute(() -> add(channel));
            } catch (ReflectiveOperationException | RuntimeException gone) {
                // Leaving: the next connection gets it from the start.
            }
        }
    }

    public void uninstall(ClassLoader server, Iterable<? extends Player> online) {
        if (listenerKey != null) {
            try {
                Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder", true, server)
                        .getMethod("removeListener", net.kyori.adventure.key.Key.class).invoke(null, listenerKey);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Shutting down anyway.
            }
            listenerKey = null;
        }
        for (Player player : online) {
            try {
                Channel channel = channelOf(player);
                channel.eventLoop().execute(() -> {
                    for (String name : new String[]{HANDLER, INBOUND}) {
                        if (channel.pipeline().get(name) != null) {
                            channel.pipeline().remove(name);
                        }
                    }
                });
            } catch (ReflectiveOperationException | RuntimeException gone) {
                // Disconnected: the pipeline went with it.
            }
        }
    }

    private void add(Channel channel) {
        if (channel.pipeline().get(HANDLER) == null && channel.pipeline().get(PACKET_HANDLER) != null) {
            channel.pipeline().addBefore(PACKET_HANDLER, INBOUND, new Inbound());
            channel.pipeline().addLast(HANDLER, new Outbound());
        }
    }

    private static Channel channelOf(Player player) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object listener = handle.getClass().getField("connection").get(handle);
        Object connection = listener.getClass().getField("connection").get(listener);
        return (Channel) connection.getClass().getField("channel").get(connection);
    }

    private final class Inbound extends io.netty.channel.ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (dropInbound(msg)) {
                return;
            }
            super.channelRead(ctx, msg);
        }
    }

    private final class Outbound extends io.netty.channel.ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            super.write(ctx, outbound(msg), promise);
        }
    }
}
