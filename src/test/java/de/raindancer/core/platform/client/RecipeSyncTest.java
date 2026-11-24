package de.raindancer.core.platform.client;

import de.raindancer.client.protocol.PacketWriter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeSyncTest {

    private static final String SHAPED = "minecraft:crafting_shaped";
    private static final String SMELTING = "minecraft:smelting";
    private static final String STONECUTTING = "minecraft:stonecutting";

    private final UUID alex = UUID.randomUUID();
    private final UUID sam = UUID.randomUUID();
    private final List<Sent> sent = new ArrayList<>();
    private final Map<String, List<RecipeSync.Encoded>> server = new LinkedHashMap<>();
    private final RecipeSync sync = new RecipeSync(wanted -> {
        Map<String, List<RecipeSync.Encoded>> found = new LinkedHashMap<>();
        server.forEach((serializer, recipes) -> {
            if (wanted.contains(serializer)) {
                found.put(serializer, recipes);
            }
        });
        return found;
    }, (player, payload) -> sent.add(new Sent(player, payload)));

    private record Sent(UUID player, byte[] payload) {
    }

    @Test
    void theServerAnnouncesWhereAFabricClientMayAskForRecipes() {
        assertThat(new String(RecipeSync.announcement(), StandardCharsets.UTF_8))
                .isEqualTo("fabric:recipe_sync/supported_serializers");
        assertThat(RecipeSync.REQUEST_CHANNEL).isEqualTo("fabric:recipe_sync/supported_serializers");
        assertThat(RecipeSync.RECIPE_CHANNEL).isEqualTo("fabric:recipe_sync");
    }

    @Test
    void aClientThatAskedGetsItsRecipesInFabricsBytesOnceItListens() {
        server.put(SHAPED, List.of(recipe("minecraft:torch", 1, 2), recipe("minecraft:chest", 3)));
        server.put(SMELTING, List.of(recipe("minecraft:glass", 9)));
        sync.requested(alex, request(SHAPED, SMELTING));
        assertThat(sent).isEmpty();

        sync.listening(alex);

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(new PacketWriter().varInt(2).toBytes());
        expected.writeBytes(new PacketWriter().string(SHAPED, 32767).varInt(2).toBytes());
        expected.writeBytes(new PacketWriter().string("minecraft:torch", 32767).toBytes());
        expected.writeBytes(new byte[] {1, 2});
        expected.writeBytes(new PacketWriter().string("minecraft:chest", 32767).toBytes());
        expected.writeBytes(new byte[] {3});
        expected.writeBytes(new PacketWriter().string(SMELTING, 32767).varInt(1).toBytes());
        expected.writeBytes(new PacketWriter().string("minecraft:glass", 32767).toBytes());
        expected.writeBytes(new byte[] {9});
        assertThat(sent).singleElement().satisfies(s -> {
            assertThat(s.player()).isEqualTo(alex);
            assertThat(s.payload()).isEqualTo(expected.toByteArray());
        });
    }

    @Test
    void onlyWhatWasAskedForAndNothingEmptyIsSent() {
        server.put(SHAPED, List.of(recipe("minecraft:torch", 1)));
        server.put(STONECUTTING, List.of(recipe("minecraft:stone_slab", 4)));
        server.put(SMELTING, List.of());
        sync.requested(alex, request(SHAPED, SMELTING, "minecraft:nonsense"));

        sync.listening(alex);

        byte[] payload = sent.getFirst().payload();
        assertThat(new String(payload, StandardCharsets.UTF_8)).contains(SHAPED).doesNotContain(STONECUTTING)
                .doesNotContain(SMELTING);
        assertThat(payload[0]).isEqualTo((byte) 1);
    }

    @Test
    void aClientWithoutTheRecipesItAskedForGetsNothing() {
        sync.requested(alex, request(SHAPED));
        sync.listening(alex);
        assertThat(sent).isEmpty();
    }

    @Test
    void aClientThatNeverAskedGetsNothing() {
        server.put(SHAPED, List.of(recipe("minecraft:torch", 1)));
        sync.listening(sam);
        assertThat(sent).isEmpty();
    }

    @Test
    void anEmptyRequestIsNoRequest() {
        server.put(SHAPED, List.of(recipe("minecraft:torch", 1)));
        sync.requested(alex, request());
        sync.listening(alex);
        assertThat(sent).isEmpty();
    }

    @Test
    void aReloadSendsAgainToEveryListeningClientButNotToOneThatLeft() {
        server.put(SHAPED, List.of(recipe("minecraft:torch", 1)));
        sync.requested(alex, request(SHAPED));
        sync.requested(sam, request(SHAPED));
        sync.listening(alex);
        sync.listening(sam);
        sync.left(sam);
        sent.clear();

        sync.reloaded();

        assertThat(sent).extracting(Sent::player).containsExactly(alex);
    }

    @Test
    void aClientThatAsksButIsNotListeningYetIsNotSentToOnReload() {
        server.put(SHAPED, List.of(recipe("minecraft:torch", 1)));
        sync.requested(alex, request(SHAPED));
        sync.reloaded();
        assertThat(sent).isEmpty();
    }

    @Test
    void aMalformedRequestIsIgnoredWithoutThrowing() {
        server.put(SHAPED, List.of(recipe("minecraft:torch", 1)));
        sync.requested(alex, new byte[] {5, 3, 'a'});
        sync.requested(sam, new PacketWriter().varInt(Integer.MAX_VALUE).toBytes());
        sync.requested(alex, new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 1});
        sync.listening(alex);
        sync.listening(sam);
        assertThat(sent).isEmpty();
    }

    @Test
    void recipesTooLargeForOnePluginMessageAreNotSentAtAll() {
        List<RecipeSync.Encoded> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(new RecipeSync.Encoded("minecraft:big_" + i, new byte[32 * 1024]));
        }
        server.put(SHAPED, many);
        sync.requested(alex, request(SHAPED));
        sync.listening(alex);
        assertThat(sent).isEmpty();
    }

    private static RecipeSync.Encoded recipe(String id, int... body) {
        byte[] bytes = new byte[body.length];
        for (int i = 0; i < body.length; i++) {
            bytes[i] = (byte) body[i];
        }
        return new RecipeSync.Encoded(id, bytes);
    }

    /** What Fabric API's client sends: a set of identifiers, a varint count and then each as a string. */
    private static byte[] request(String... serializers) {
        return new PacketWriter().list(Set.of(serializers), (w, s) -> w.string(s, 32767)).toBytes();
    }
}
