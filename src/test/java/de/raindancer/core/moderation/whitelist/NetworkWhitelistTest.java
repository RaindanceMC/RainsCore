package de.raindancer.core.moderation.whitelist;

import de.raindancer.core.data.store.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** One whitelist for every server that takes part; a server can stay out and keep its own. */
class NetworkWhitelistTest {

    @TempDir
    Path folder;

    private static final UUID ADA = UUID.randomUUID();
    private static final UUID BO = UUID.randomUUID();
    private static final UUID CY = UUID.randomUUID();

    /** A server's own whitelist. */
    private static final class Listed implements NetworkWhitelist.Server {
        final Map<UUID, String> players = new LinkedHashMap<>();
        NetworkWhitelist owner;

        @Override
        public Map<UUID, String> whitelisted() {
            return Map.copyOf(players);
        }

        @Override
        public void whitelist(UUID player, String name, boolean on) {
            // Like the real server: changing the list fires the event, which comes back to the whitelist.
            boolean changed = on ? players.put(player, name) == null : players.remove(player) != null;
            if (changed && owner != null) {
                owner.changedHere(player, name, on);
            }
        }
    }

    private YamlStore shared;

    @BeforeEach
    void setUp() {
        shared = new YamlStore(folder.resolve("whitelist.yml"));
    }

    private NetworkWhitelist server(String id, Listed listed) {
        NetworkWhitelist whitelist = new NetworkWhitelist(shared, id, listed);
        listed.owner = whitelist;
        return whitelist;
    }

    @Test
    @DisplayName("the first time a server takes part, its own list is added to the network's — nobody is lost")
    void joiningMerges() {
        Listed smp = new Listed();
        smp.players.put(ADA, "Ada");
        Listed hub = new Listed();
        hub.players.put(BO, "Bo");

        server("smp", smp).start();
        server("hub", hub).start();
        server("smp", smp).start();

        assertThat(smp.players).containsOnlyKeys(ADA, BO);
        assertThat(hub.players).containsOnlyKeys(ADA, BO);
    }

    @Test
    @DisplayName("whitelisting on one server whitelists on the others once they read it")
    void addSpreads() {
        Listed smp = new Listed();
        Listed hub = new Listed();
        NetworkWhitelist onSmp = server("smp", smp);
        NetworkWhitelist onHub = server("hub", hub);
        onSmp.start();
        onHub.start();

        smp.whitelist(CY, "Cy", true);
        onHub.reload();

        assertThat(hub.players).containsEntry(CY, "Cy");
    }

    @Test
    @DisplayName("taking somebody off on one server takes them off everywhere, also on a server that was down")
    void removeSpreads() {
        Listed smp = new Listed();
        smp.players.put(ADA, "Ada");
        Listed hub = new Listed();
        NetworkWhitelist onSmp = server("smp", smp);
        onSmp.start();
        server("hub", hub).start();

        smp.whitelist(ADA, "Ada", false);
        server("hub", hub).start();

        assertThat(hub.players).doesNotContainKey(ADA);
    }

    @Test
    @DisplayName("a server that stays out keeps its own list and changes nobody else's")
    void stayingOut() {
        Listed smp = new Listed();
        smp.players.put(ADA, "Ada");
        server("smp", smp).start();
        Listed event = new Listed();
        event.players.put(BO, "Bo");
        NetworkWhitelist onEvent = server("event", event);
        onEvent.taking(false);

        onEvent.start();
        event.whitelist(CY, "Cy", true);

        assertThat(event.players).containsOnlyKeys(BO, CY);
        assertThat(smp.players).containsOnlyKeys(ADA);
        Listed later = new Listed();
        server("later", later).start();
        assertThat(later.players).containsOnlyKeys(ADA);
    }
}
