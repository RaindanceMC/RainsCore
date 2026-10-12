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

/** Who is online on the other server of the link — for a name typed here about somebody over there. */
class LinkRosterTest {

    private static final String SECRET = "a-shared-secret-of-reasonable-length";
    private final List<ServerLink> open = new ArrayList<>();

    @AfterEach
    void closeAll() {
        open.forEach(ServerLink::close);
    }

    @Test
    @DisplayName("one exchange and each side knows who is on the other; a roster not heard from for long is forgotten")
    void exchange() throws Exception {
        LinkHost host = LinkHost.listen("127.0.0.1", 0, SECRET, Duration.ofSeconds(2));
        open.add(host);
        LinkGuest guest = LinkGuest.connect(URI.create("http://127.0.0.1:" + host.port()), SECRET,
                Duration.ofSeconds(2), Duration.ofMillis(50));
        open.add(guest);
        AtomicLong clock = new AtomicLong(1_000);
        LinkRoster arena = new LinkRoster(host, () -> List.of("Engine_963"), clock::get);
        LinkRoster survival = new LinkRoster(guest, () -> List.of("Raindancer118", "Hydr0Drag0n"), clock::get);

        survival.exchange().get();

        assertThat(survival.elsewhere()).containsExactlyInAnyOrder("Engine_963");
        assertThat(arena.elsewhere()).containsExactlyInAnyOrder("Raindancer118", "Hydr0Drag0n");

        clock.addAndGet(LinkRoster.FORGOTTEN_AFTER.toMillis() + 1);
        assertThat(survival.elsewhere()).as("stale — the other side may be down").isEmpty();
        assertThat(arena.elsewhere()).isEmpty();
    }

    @Test
    @DisplayName("names are names: no empty ones, none with a comma slipped in")
    void clean() {
        assertThat(LinkRoster.parse("Ada,,Bo, ")).containsExactlyInAnyOrder("Ada", "Bo");
        assertThat(LinkRoster.join(List.of("Ada", "Bo"))).isEqualTo("Ada,Bo");
    }

    @Test
    @DisplayName("each side says what it is called, so a list of players can say where they are")
    void named() throws Exception {
        LinkHost host = LinkHost.listen("127.0.0.1", 0, SECRET, Duration.ofSeconds(2));
        open.add(host);
        LinkGuest guest = LinkGuest.connect(URI.create("http://127.0.0.1:" + host.port()), SECRET,
                Duration.ofSeconds(2), Duration.ofMillis(50));
        open.add(guest);
        AtomicLong clock = new AtomicLong(1_000);
        LinkRoster arena = new LinkRoster(host, "Arena", () -> List.of("Engine_963"), clock::get);
        LinkRoster survival = new LinkRoster(guest, "SMP", () -> List.of("Ada"), clock::get);

        survival.exchange().get();

        assertThat(survival.serverName()).isEqualTo("Arena");
        assertThat(arena.serverName()).isEqualTo("SMP");
    }
}
