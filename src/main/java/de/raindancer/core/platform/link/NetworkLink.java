package de.raindancer.core.platform.link;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * This server's one {@link ServerLink}, as {@code link.yml} describes it — one per server, because one port can only be
 * listened on once, and every plugin that wants to talk to the other server shares it by topic.
 */
public final class NetworkLink {

    /** How long a poll is held open. Short enough that a dead connection is noticed, long enough to cost nothing. */
    public static final Duration POLL_WAIT = Duration.ofSeconds(20);
    private static final Duration RETRY = Duration.ofSeconds(3);

    private static final String DEFAULT = """
            # The direct link between this server and another of the same network — for example an arena server
            # behind the same proxy. One server hosts, the other connects as its guest; both need the same secret.
            # Plugins share it: whatever wants to talk to the other server sends on its own topic.
            #
            # role: off | host | guest
            role: off

            # host: where to listen. Inside a container, 0.0.0.0 — with the port mapped to a private address only.
            # This is not for the open internet.
            listen-address: 0.0.0.0
            listen-port: 25594

            # guest: where the host is.
            host-url: http://172.18.0.1:25594

            # At least 24 characters, the same on both servers. Treat it like a password.
            secret: ""
            """;

    private NetworkLink() {
    }

    /** Reads {@code file} (writing the switched-off default when there is none) and starts the end it describes. */
    public static Optional<ServerLink> start(Path file, LogChannel log) {
        if (!Files.exists(file)) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULT);
            } catch (IOException failure) {
                log.warn("Could not write {}: {}", file.getFileName(), failure.getMessage());
            }
            return Optional.empty();
        }
        YamlStore store = new YamlStore(file);
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            log.error("{} could not be read ({}); there is no server link.", file.getFileName(), store.problems());
            return Optional.empty();
        }
        String role = yaml.getString("role", "off").trim().toLowerCase(Locale.ROOT);
        if (role.equals("off")) {
            return Optional.empty();
        }
        String secret = yaml.getString("secret", "");
        if (secret == null || secret.length() < LinkAuth.SHORTEST_SECRET) {
            log.error("The server link is set to '{}' but its secret is shorter than {} characters; it is not started.",
                    role, LinkAuth.SHORTEST_SECRET);
            return Optional.empty();
        }
        try {
            return switch (role) {
                case "host" -> {
                    LinkHost host = LinkHost.listen(yaml.getString("listen-address", "0.0.0.0"),
                            yaml.getInt("listen-port", 25594), secret, POLL_WAIT);
                    log.info("Server link: listening on port {}.", host.port());
                    yield Optional.of(host);
                }
                case "guest" -> {
                    URI url = URI.create(yaml.getString("host-url", ""));
                    log.info("Server link: connecting to {}.", url);
                    yield Optional.of(LinkGuest.connect(url, secret, POLL_WAIT, RETRY));
                }
                default -> {
                    log.error("The server link's role '{}' is none of off, host, guest.", role);
                    yield Optional.empty();
                }
            };
        } catch (IOException | IllegalArgumentException failure) {
            log.error("The server link could not start: {}", failure.getMessage());
            return Optional.empty();
        }
    }
}
