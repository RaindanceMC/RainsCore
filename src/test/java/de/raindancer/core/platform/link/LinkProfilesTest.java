package de.raindancer.core.platform.link;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** A player's data, kept on one server, shown on the other: fetched by part when they arrive. */
class LinkProfilesTest {

    private static final String SECRET = "a-shared-secret-of-reasonable-length";
    private final List<ServerLink> open = new ArrayList<>();

    @AfterEach
    void closeAll() {
        open.forEach(ServerLink::close);
    }

    @Test
    @DisplayName("the follower fetches every part it follows; a part the other side does not keep changes nothing")
    void fetch() throws Exception {
        LinkHost host = LinkHost.listen("127.0.0.1", 0, SECRET, Duration.ofSeconds(2));
        open.add(host);
        LinkGuest guest = LinkGuest.connect(URI.create("http://127.0.0.1:" + host.port()), SECRET,
                Duration.ofSeconds(2), Duration.ofMillis(50));
        open.add(guest);
        UUID ada = UUID.randomUUID();
        LinkProfiles survival = new LinkProfiles(guest);
        survival.provide("identity", player -> player.equals(ada) ? Map.of("colour", "#ff00aa", "prefix", "<gold>[VIP] ")
                : Map.of());
        LinkProfiles arena = new LinkProfiles(host);
        Map<String, Map<String, String>> applied = new ConcurrentHashMap<>();
        arena.follow("identity", (player, data) -> applied.put("identity", data));
        arena.follow("cosmetics", (player, data) -> applied.put("cosmetics", data));

        arena.fetch(ada).get();

        assertThat(applied.get("identity")).containsEntry("colour", "#ff00aa").containsEntry("prefix", "<gold>[VIP] ");
        assertThat(applied).as("survival keeps no cosmetics part here").doesNotContainKey("cosmetics");
    }
}
