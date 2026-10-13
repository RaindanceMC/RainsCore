package de.raindancer.core.platform.link;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** A file kept on the authority server, mirrored on a following one. */
class FileSyncTest {

    private static final String SECRET = "a-shared-secret-of-reasonable-length";
    private final List<ServerLink> open = new ArrayList<>();

    @TempDir
    Path root;

    @AfterEach
    void closeAll() {
        open.forEach(ServerLink::close);
    }

    private record Pair(FileSync authority, FileSync follower, Path authorityDir, Path followerDir) {
    }

    private Pair pair() throws Exception {
        LinkHost host = LinkHost.listen("127.0.0.1", 0, SECRET, Duration.ofSeconds(2));
        open.add(host);
        LinkGuest guest = LinkGuest.connect(URI.create("http://127.0.0.1:" + host.port()), SECRET,
                Duration.ofSeconds(2), Duration.ofMillis(50));
        open.add(guest);
        Path a = Files.createDirectories(root.resolve("authority"));
        Path f = Files.createDirectories(root.resolve("follower"));
        return new Pair(new FileSync(a, List.of(guest), null), new FileSync(f, List.of(), host), a, f);
    }

    @Test
    @DisplayName("a registered file is copied to the follower, which is told once; unchanged, it is left alone")
    void copiesAndTellsOnce() throws Exception {
        Pair pair = pair();
        Files.createDirectories(pair.authorityDir().resolve("RainsChat"));
        Files.writeString(pair.authorityDir().resolve("RainsChat/config.yml"), "format: '<name>: <message>'\n");
        AtomicInteger told = new AtomicInteger();
        pair.authority().register("RainsChat/config.yml", () -> { });
        pair.follower().register("RainsChat/config.yml", told::incrementAndGet);

        assertThat(pair.follower().pull("RainsChat/config.yml").get()).isTrue();
        assertThat(Files.readString(pair.followerDir().resolve("RainsChat/config.yml")))
                .isEqualTo("format: '<name>: <message>'\n");
        assertThat(told.get()).isEqualTo(1);

        assertThat(pair.follower().pull("RainsChat/config.yml").get()).isFalse();
        assertThat(told.get()).isEqualTo(1);

        Files.writeString(pair.authorityDir().resolve("RainsChat/config.yml"), "format: '[x] <name>: <message>'\n");
        assertThat(pair.follower().pull("RainsChat/config.yml").get()).isTrue();
        assertThat(told.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a rewrite of the local copy is not undone and does not tell again while the other side is unchanged")
    void localRewriteIsNotAChange() throws Exception {
        Pair pair = pair();
        Files.writeString(pair.authorityDir().resolve("a.yml"), "x: 1\n");
        AtomicInteger told = new AtomicInteger();
        pair.authority().register("a.yml", () -> { });
        pair.follower().register("a.yml", told::incrementAndGet);
        pair.follower().pull("a.yml").get();

        Files.writeString(pair.followerDir().resolve("a.yml"), "x: 1\ny: default-written-back\n");

        assertThat(pair.follower().pull("a.yml").get()).isFalse();
        assertThat(told.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a file the authority has not registered, or does not have, is never served and the follower keeps its own")
    void unregisteredAndMissing() throws Exception {
        Pair pair = pair();
        Files.writeString(pair.authorityDir().resolve("secret.yml"), "token: abc\n");
        Files.writeString(pair.followerDir().resolve("secret.yml"), "mine: true\n");
        pair.follower().register("secret.yml", () -> { });
        pair.follower().register("absent.yml", () -> { });
        pair.authority().register("absent.yml", () -> { });

        assertThat(pair.follower().pull("secret.yml").get()).isFalse();
        assertThat(Files.readString(pair.followerDir().resolve("secret.yml"))).isEqualTo("mine: true\n");
        assertThat(pair.follower().pull("absent.yml").get()).isFalse();
    }

    @Test
    @DisplayName("a path that climbs out of the plugins folder is refused on both sides")
    void noClimbing() throws Exception {
        Pair pair = pair();
        Files.writeString(root.resolve("outside.txt"), "nope");
        pair.authority().register("../outside.txt", () -> { });
        pair.follower().register("../outside.txt", () -> { });

        assertThat(pair.follower().pull("../outside.txt").get()).isFalse();
        assertThat(Files.exists(root.resolve("outside.txt.copy"))).isFalse();
        assertThat(pair.follower().isRegistered("../outside.txt")).isFalse();
    }
}
