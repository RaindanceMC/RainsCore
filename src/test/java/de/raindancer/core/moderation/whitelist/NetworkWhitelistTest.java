package de.raindancer.core.moderation.whitelist;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One whitelist for the network, kept in the core database: every backend that takes part mirrors it into its vanilla
 * list, and whatever changes that list — /whitelist, a plugin — changes the network's.
 */
@Tag("database")
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
        int waitedFor;

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

        @Override
        public void whitelistNow(UUID player, String name) {
            waitedFor++;
            whitelist(player, name, true);
        }
    }

    private Database database;
    private YamlStore document;
    private final AtomicInteger announced = new AtomicInteger();

    @BeforeEach
    void setUp() {
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        document = new YamlStore(folder.resolve("whitelist.yml"));
    }

    @AfterEach
    void close() {
        database.close();
    }

    private NetworkWhitelist server(String id, Listed listed) {
        NetworkWhitelist whitelist = new NetworkWhitelist(new WhitelistTable(database, () -> 1L), document, id, listed,
                announced::incrementAndGet);
        listed.owner = whitelist;
        return whitelist;
    }

    @Test
    @DisplayName("the old network document and a new server's own list come in, and every mirror shows the network's")
    void joining() {
        document.update(yaml -> {
            yaml.set("players." + ADA, "Ada");
            yaml.set("servers", List.of("smp"));
        });
        Listed smp = new Listed();
        smp.players.put(ADA, "Ada");
        Listed ctf = new Listed();
        ctf.players.put(BO, "Bo");

        server("smp", smp).start();
        server("ctf", ctf).start();
        server("smp", smp).start();

        assertThat(smp.players).containsOnlyKeys(ADA, BO);
        assertThat(ctf.players).containsOnlyKeys(ADA, BO);
    }

    @Test
    @DisplayName("/whitelist add on one server reaches the others when they hear it — announced once, echoes are not")
    void addSpreads() {
        Listed smp = new Listed();
        Listed hub = new Listed();
        NetworkWhitelist onSmp = server("smp", smp);
        NetworkWhitelist onHub = server("hub", hub);
        onSmp.start();
        onHub.start();
        announced.set(0);

        smp.whitelist(CY, "Cy", true);
        onHub.reload();
        onHub.reload();

        assertThat(hub.players).containsEntry(CY, "Cy");
        assertThat(announced).as("the hub's own mirror event is no news").hasValue(1);
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
        assertThat(hub.players).containsOnlyKeys(ADA);

        smp.whitelist(ADA, "Ada", false);
        server("hub", hub).start();

        assertThat(hub.players).doesNotContainKey(ADA);
    }

    @Test
    @DisplayName("Discord's /link writes the network's list: on it at once, and on this server's mirror too")
    void addedFromDiscord() {
        Listed smp = new Listed();
        NetworkWhitelist onSmp = server("smp", smp);
        onSmp.start();
        announced.set(0);

        assertThat(onSmp.add(BO, "Bo", "discord:42")).isTrue();

        assertThat(onSmp.isWhitelisted(BO)).isTrue();
        assertThat(onSmp.isWhitelisted(CY)).isFalse();
        assertThat(smp.players).containsKey(BO);
        assertThat(announced).hasValue(1);
        assertThat(new WhitelistTable(database, () -> 1L).contains(BO)).contains(true);
    }

    @Test
    @DisplayName("at the door: somebody on the network's list but not yet on this mirror is put on it before vanilla looks")
    void catchUp() {
        Listed smp = new Listed();
        Listed hub = new Listed();
        NetworkWhitelist onSmp = server("smp", smp);
        NetworkWhitelist onHub = server("hub", hub);
        onSmp.start();
        onHub.start();
        onSmp.add(BO, "Bo", "discord:42");

        onHub.catchUp(BO, "Bo");
        onHub.catchUp(CY, "Cy");
        onHub.catchUp(BO, "Bo");

        assertThat(hub.players).containsOnlyKeys(BO);
        assertThat(hub.waitedFor).as("only when missing").isEqualTo(1);
    }

    @Test
    @DisplayName("with the database gone, a server answers from its mirror and changes nothing")
    void databaseDown() {
        Listed smp = new Listed();
        smp.players.put(ADA, "Ada");
        NetworkWhitelist onSmp = server("smp", smp);
        onSmp.start();
        database.close();

        onSmp.reload();
        onSmp.catchUp(BO, "Bo");

        assertThat(smp.players).containsOnlyKeys(ADA);
        assertThat(onSmp.isWhitelisted(ADA)).isTrue();
        assertThat(onSmp.add(BO, "Bo", null)).isFalse();
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
        onEvent.catchUp(ADA, "Ada");

        assertThat(event.players).containsOnlyKeys(BO, CY);
        assertThat(smp.players).containsOnlyKeys(ADA);
        Listed later = new Listed();
        server("later", later).start();
        assertThat(later.players).containsOnlyKeys(ADA);
    }
}
