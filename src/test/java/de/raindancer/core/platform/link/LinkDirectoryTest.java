package de.raindancer.core.platform.link;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Every link of this server, found by its name, by what the other side calls itself, or by who is over there. */
class LinkDirectoryTest {

    private static final String SECRET = "a-shared-secret-of-reasonable-length";
    private final List<ServerLink> open = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000);

    @AfterEach
    void closeAll() {
        open.forEach(ServerLink::close);
    }

    private LinkRoster[] pair(String hostName, List<String> hostPlayers, List<String> guestPlayers) throws Exception {
        LinkHost host = LinkHost.listen("127.0.0.1", 0, SECRET, Duration.ofSeconds(2));
        open.add(host);
        LinkGuest guest = LinkGuest.connect(URI.create("http://127.0.0.1:" + host.port()), SECRET,
                Duration.ofSeconds(2), Duration.ofMillis(50));
        open.add(guest);
        LinkRoster there = new LinkRoster(host, hostName, () -> hostPlayers, clock::get);
        LinkRoster here = new LinkRoster(guest, "SMP", () -> guestPlayers, clock::get);
        here.exchange().get();
        return new LinkRoster[]{here, there};
    }

    @Test
    @DisplayName("a link by its key in link.yml or by the other side's server-name, case aside")
    void byName() throws Exception {
        LinkRoster arena = pair("Arena", List.of(), List.of())[0];
        LinkRoster oneblock = pair("OneBlock", List.of(), List.of())[0];
        LinkDirectory directory = new LinkDirectory();
        directory.add("", open.get(1), arena);
        directory.add("oneblock", open.get(3), oneblock);

        assertThat(directory.named("oneblock")).containsSame(open.get(3));
        assertThat(directory.named("ONEBLOCK")).containsSame(open.get(3));
        assertThat(directory.named("arena")).as("by server-name").containsSame(open.get(1));
        assertThat(directory.named("")).as("the main link").containsSame(open.get(1));
        assertThat(directory.named("nowhere")).isEmpty();
        assertThat(directory.all()).containsOnlyKeys("", "oneblock");
    }

    @Test
    @DisplayName("the link somebody is reachable over, by their name — none once their server has gone quiet")
    void reaching() throws Exception {
        LinkRoster arena = pair("Arena", List.of("Engine_963"), List.of())[0];
        LinkRoster hub = pair("Hub", List.of("Hydr0Drag0n"), List.of())[0];
        LinkDirectory directory = new LinkDirectory();
        directory.add("", open.get(1), arena);
        directory.add("hub", open.get(3), hub);

        assertThat(directory.reaching("hydr0drag0n")).containsSame(open.get(3));
        assertThat(directory.reaching("Engine_963")).containsSame(open.get(1));
        assertThat(directory.reaching("Raindancer118")).isEmpty();
        assertThat(directory.serverOf("Engine_963")).contains("Arena");

        clock.addAndGet(LinkRoster.FORGOTTEN_AFTER.toMillis() + 1);
        assertThat(directory.reaching("Engine_963")).isEmpty();
    }

    @Test
    @DisplayName("a server without links has an empty directory, not a missing one")
    void empty() {
        assertThat(LinkDirectory.NONE.all()).isEmpty();
        assertThat(LinkDirectory.NONE.named("")).isEmpty();
        assertThat(LinkDirectory.NONE.reaching("Ada")).isEmpty();
    }
}
