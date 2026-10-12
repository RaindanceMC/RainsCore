package de.raindancer.core.platform.link;

import de.raindancer.core.platform.log.Log;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkLinkTest {

    private static final String SECRET = "the-same-secret-on-both-servers-123";

    @TempDir
    Path folder;

    @Test
    @DisplayName("without a file one is written, switched off, and no link starts")
    void defaultIsOff() throws IOException {
        Path file = folder.resolve("link.yml");

        assertThat(NetworkLink.start(file, Log.of("test"))).isEmpty();

        assertThat(Files.readString(file)).contains("role: off").contains("secret:");
    }

    @Test
    @DisplayName("a role without a usable secret starts nothing — an open link is worse than none")
    void noSecretNoLink() throws IOException {
        Path file = folder.resolve("link.yml");
        Files.writeString(file, "role: host\nlisten-address: 127.0.0.1\nlisten-port: 0\nsecret: short\n");

        assertThat(NetworkLink.start(file, Log.of("test"))).isEmpty();
    }

    @Test
    @DisplayName("a host file and a guest file pointing at it make two ends that talk")
    void hostAndGuest() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        Path hostFile = folder.resolve("host.yml");
        Files.writeString(hostFile, "role: host\nlisten-address: 127.0.0.1\nlisten-port: " + port
                + "\nsecret: " + SECRET + "\n");
        Path guestFile = folder.resolve("guest.yml");
        Files.writeString(guestFile, "role: guest\nhost-url: http://127.0.0.1:" + port + "\nsecret: " + SECRET + "\n");

        Optional<ServerLink> host = NetworkLink.start(hostFile, Log.of("test"));
        Optional<ServerLink> guest = NetworkLink.start(guestFile, Log.of("test"));
        try {
            assertThat(host).containsInstanceOf(LinkHost.class);
            assertThat(guest).containsInstanceOf(LinkGuest.class);
            host.get().on("ping", data -> Map.of("pong", data.get("n")));

            assertThat(guest.get().request("ping", Map.of("n", "7"), Duration.ofSeconds(3)).get(4, TimeUnit.SECONDS))
                    .containsEntry("pong", "7");
            host.get().off("ping");
            assertThat(guest.get().request("ping", Map.of("n", "8"), Duration.ofSeconds(3)).get(4, TimeUnit.SECONDS))
                    .as("a topic switched off answers nothing").isEmpty();
        } finally {
            host.ifPresent(ServerLink::close);
            guest.ifPresent(ServerLink::close);
        }
    }

    @Test
    @DisplayName("further links under peers: each its own end, the main one untouched; the server's name is read")
    void peers() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        Path hub = folder.resolve("hub.yml");
        Files.writeString(hub, "role: host\nlisten-address: 127.0.0.1\nlisten-port: " + port + "\nsecret: " + SECRET
                + "\nserver-name: Hub\nfollows: true\n");
        Path smp = folder.resolve("smp.yml");
        Files.writeString(smp, "role: off\nserver-name: SMP\npeers:\n  hub:\n    role: guest\n    host-url: http://127.0.0.1:"
                + port + "\n    secret: " + SECRET + "\n");

        Optional<ServerLink> hubEnd = NetworkLink.start(hub, Log.of("test"));
        Map<String, ServerLink> smpPeers = NetworkLink.peers(smp, Log.of("test"));
        try {
            assertThat(NetworkLink.serverName(smp)).isEqualTo("SMP");
            assertThat(NetworkLink.serverName(hub)).isEqualTo("Hub");
            assertThat(smpPeers).containsKey("hub");
            hubEnd.orElseThrow().on("ping", data -> Map.of("pong", data.get("n")));
            assertThat(smpPeers.get("hub").request("ping", Map.of("n", "7"), Duration.ofSeconds(5))
                    .get(5, TimeUnit.SECONDS)).containsEntry("pong", "7");
        } finally {
            hubEnd.ifPresent(ServerLink::close);
            smpPeers.values().forEach(ServerLink::close);
        }
    }
}
