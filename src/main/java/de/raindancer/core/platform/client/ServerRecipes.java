package de.raindancer.core.platform.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What {@link RecipeSync} needs from the server itself: its recipes, encoded by the server's own stream codecs,
 * and a {@code minecraft:register} sent during configuration, which Paper's API cannot (it announces channels
 * only once the player is in play, and refuses to send on a channel the client did not register).
 *
 * <p>Reflective, since Core compiles against the API only; {@link #resolve} fails on a server it does not fit.
 */
final class ServerRecipes {

    private final Method getServer;
    private final Method recipeManager;
    private final Method allRecipes;
    private final Method registryAccess;
    private final Method holderId;
    private final Method holderValue;
    private final Method keyIdentifier;
    private final Method serializerOf;
    private final Object serializerRegistry;
    private final Method registryKey;
    private final Method streamCodec;
    private final Method encode;
    private final Constructor<?> newBuffer;
    private final Method parseIdentifier;
    private final Constructor<?> newPayload;
    private final Constructor<?> newPacket;
    private final Field packetListener;
    private final Method send;

    private ServerRecipes(ClassLoader server) throws ReflectiveOperationException {
        Class<?> minecraftServer = Class.forName("net.minecraft.server.MinecraftServer", false, server);
        Class<?> manager = Class.forName("net.minecraft.world.item.crafting.RecipeManager", false, server);
        Class<?> holder = Class.forName("net.minecraft.world.item.crafting.RecipeHolder", false, server);
        Class<?> recipe = Class.forName("net.minecraft.world.item.crafting.Recipe", false, server);
        Class<?> serializer = Class.forName("net.minecraft.world.item.crafting.RecipeSerializer", false, server);
        Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey", false, server);
        Class<?> registry = Class.forName("net.minecraft.core.Registry", false, server);
        Class<?> registryAccessType = Class.forName("net.minecraft.core.RegistryAccess", false, server);
        Class<?> buffer = Class.forName("net.minecraft.network.RegistryFriendlyByteBuf", false, server);
        Class<?> encoder = Class.forName("net.minecraft.network.codec.StreamEncoder", false, server);
        Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", false, server);
        Class<?> payload = Class.forName("net.minecraft.network.protocol.common.custom.DiscardedPayload", false, server);
        Class<?> payloadType = Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload", false, server);
        Class<?> packet = Class.forName("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket", false, server);
        Class<?> anyPacket = Class.forName("net.minecraft.network.protocol.Packet", false, server);
        Class<?> commonListener = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl", false, server);
        Class<?> paperConnection = Class.forName("io.papermc.paper.connection.PaperCommonConnection", false, server);

        getServer = minecraftServer.getMethod("getServer");
        recipeManager = minecraftServer.getMethod("getRecipeManager");
        allRecipes = manager.getMethod("getRecipes");
        registryAccess = minecraftServer.getMethod("registryAccess");
        holderId = holder.getMethod("id");
        holderValue = holder.getMethod("value");
        keyIdentifier = resourceKey.getMethod("identifier");
        serializerOf = recipe.getMethod("getSerializer");
        serializerRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, server)
                .getField("RECIPE_SERIALIZER").get(null);
        registryKey = registry.getMethod("getKey", Object.class);
        streamCodec = serializer.getMethod("streamCodec");
        encode = encoder.getMethod("encode", Object.class, Object.class);
        newBuffer = buffer.getConstructor(ByteBuf.class, registryAccessType);
        parseIdentifier = identifier.getMethod("parse", String.class);
        newPayload = payload.getConstructor(identifier, byte[].class);
        newPacket = packet.getConstructor(payloadType);
        packetListener = paperConnection.getDeclaredField("packetListener");
        packetListener.setAccessible(true);
        send = commonListener.getMethod("send", anyPacket);
    }

    static ServerRecipes resolve(ClassLoader server) throws ReflectiveOperationException {
        return new ServerRecipes(server);
    }

    /** The server's recipes of these serializers, each written by its serializer's stream codec. */
    Map<String, List<RecipeSync.Encoded>> bySerializer(Set<String> serializers) {
        try {
            Object minecraft = getServer.invoke(null);
            Object registries = registryAccess.invoke(minecraft);
            Map<String, List<RecipeSync.Encoded>> found = new LinkedHashMap<>();
            for (Object holder : (Collection<?>) allRecipes.invoke(recipeManager.invoke(minecraft))) {
                Object recipe = holderValue.invoke(holder);
                Object serializer = serializerOf.invoke(recipe);
                Object serializerId = registryKey.invoke(serializerRegistry, serializer);
                if (serializerId == null || !serializers.contains(serializerId.toString())) {
                    continue;
                }
                String id = keyIdentifier.invoke(holderId.invoke(holder)).toString();
                found.computeIfAbsent(serializerId.toString(), any -> new ArrayList<>())
                        .add(new RecipeSync.Encoded(id, encode(registries, streamCodec.invoke(serializer), recipe)));
            }
            return found;
        } catch (ReflectiveOperationException failed) {
            throw new IllegalStateException("could not read the server's recipes", failed);
        }
    }

    private byte[] encode(Object registries, Object codec, Object recipe) throws ReflectiveOperationException {
        ByteBuf raw = Unpooled.buffer();
        try {
            encode.invoke(codec, newBuffer.newInstance(raw, registries), recipe);
            byte[] bytes = new byte[raw.readableBytes()];
            raw.readBytes(bytes);
            return bytes;
        } finally {
            raw.release();
        }
    }

    /** Sends {@code minecraft:register} with this body on a connection still in configuration. */
    void register(Object configurationConnection, byte[] channels) throws ReflectiveOperationException {
        Object payload = newPayload.newInstance(parseIdentifier.invoke(null, "minecraft:register"), channels);
        send.invoke(packetListener.get(configurationConnection), newPacket.newInstance(payload));
    }
}
