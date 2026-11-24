package de.raindancer.core.platform.client;

import de.raindancer.client.protocol.MalformedPacketException;
import de.raindancer.client.protocol.PacketReader;
import de.raindancer.client.protocol.PacketWriter;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full recipes for Fabric clients that ask for them, what recipe viewers like JEI, REI and EMI show. Since 1.21.2
 * a server sends a client only what its recipe book needs; Fabric API's recipe sync fills that gap, but only
 * when the server speaks it, and Paper does not.
 *
 * <p>The exchange, as Fabric API 26.3 does it: the server lists {@link #REQUEST_CHANNEL} in a
 * {@code minecraft:register} during configuration, before the client is asked for its known packs. Only then
 * does the client say which recipe serializers it can read, and it does so in configuration. Once it listens on
 * {@link #RECIPE_CHANNEL} in play, it is sent those recipes, grouped by serializer, and again after a reload.
 * A serializer the client did not ask for would make it drop the whole payload, so nothing else is sent.
 *
 * <p>The recipes themselves are encoded by the server's own codecs ({@link Recipes}); this class only frames them.
 */
public final class RecipeSync {

    public static final String REQUEST_CHANNEL = "fabric:recipe_sync/supported_serializers";
    public static final String RECIPE_CHANNEL = "fabric:recipe_sync";
    // A clientbound plugin message carries at most 1 MiB; Paper would kick the player over a larger one.
    // Fabric splits larger payloads with its own protocol, which is not spoken here.
    static final int MAX_PAYLOAD = 1_048_576 - 64;
    private static final int MAX_IDENTIFIER = 32_767;
    private static final int MAX_SERIALIZERS = 8_192;

    private static final LogChannel log = Log.of("recipes");

    /** One recipe as the server's codec writes it, under its id. */
    public record Encoded(String id, byte[] body) {
    }

    @FunctionalInterface
    public interface Recipes {
        /** The server's recipes of these serializers, encoded, by serializer id; unknown ids are left out. */
        Map<String, List<Encoded>> bySerializer(Set<String> serializers);
    }

    @FunctionalInterface
    public interface Out {
        void send(UUID player, byte[] payload);
    }

    private final Recipes recipes;
    private final Out out;
    private final Map<UUID, Set<String>> asked = new ConcurrentHashMap<>();
    private final Set<UUID> listening = ConcurrentHashMap.newKeySet();

    public RecipeSync(Recipes recipes, Out out) {
        this.recipes = recipes;
        this.out = out;
    }

    /** The body of the {@code minecraft:register} that lets a Fabric client ask. */
    public static byte[] announcement() {
        return REQUEST_CHANNEL.getBytes(StandardCharsets.UTF_8);
    }

    /** The client's list of serializers it can read, as it arrived during configuration. Never throws. */
    public void requested(UUID player, byte[] request) {
        Set<String> serializers;
        try {
            PacketReader reader = new PacketReader(request);
            serializers = new HashSet<>(reader.list(MAX_SERIALIZERS, r -> r.string(MAX_IDENTIFIER)));
        } catch (MalformedPacketException | IndexOutOfBoundsException malformed) {
            log.debug("Ignored a malformed recipe sync request from {}: {}", player, malformed.getMessage());
            return;
        }
        if (serializers.isEmpty()) {
            asked.remove(player);
        } else {
            asked.put(player, Set.copyOf(serializers));
        }
    }

    /** The client listens on {@link #RECIPE_CHANNEL} now: in play, after the vanilla recipes. */
    public void listening(UUID player) {
        if (asked.containsKey(player)) {
            listening.add(player);
            send(player);
        }
    }

    /** Recipes changed (a data pack reload): everybody listening gets them again. */
    public void reloaded() {
        listening.forEach(this::send);
    }

    public void left(UUID player) {
        asked.remove(player);
        listening.remove(player);
    }

    private void send(UUID player) {
        Set<String> serializers = asked.get(player);
        if (serializers == null) {
            return;
        }
        byte[] payload = payload(recipes.bySerializer(serializers));
        if (payload == null) {
            return;
        }
        if (payload.length > MAX_PAYLOAD) {
            log.warn("Recipes for {} come to {} bytes, more than one plugin message carries; not sent.",
                    player, payload.length);
            return;
        }
        out.send(player, payload);
    }

    /** {@code list(serializer id, list(recipe id, recipe))}, as Fabric's ClientboundRecipeSyncPayload reads it. */
    private static byte[] payload(Map<String, List<Encoded>> bySerializer) {
        Map<String, List<Encoded>> present = new LinkedHashMap<>();
        bySerializer.forEach((serializer, list) -> {
            if (!list.isEmpty()) {
                present.put(serializer, list);
            }
        });
        if (present.isEmpty()) {
            return null;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new PacketWriter().varInt(present.size()).toBytes());
        present.forEach((serializer, list) -> {
            bytes.writeBytes(new PacketWriter().string(serializer, MAX_IDENTIFIER).varInt(list.size()).toBytes());
            for (Encoded recipe : list) {
                bytes.writeBytes(new PacketWriter().string(recipe.id(), MAX_IDENTIFIER).toBytes());
                bytes.writeBytes(recipe.body());
            }
        });
        return bytes.toByteArray();
    }
}
