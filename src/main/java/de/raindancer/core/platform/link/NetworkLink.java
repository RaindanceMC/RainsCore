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

            # What this server is called to the others, e.g. SMP, Arena, Hub — shown where people are listed.
            server-name: ""

            # true on a server that shows the other one's player data — name styles, prefixes, nicknames — fetched
            # when a player arrives (an arena next to a survival server). The other server keeps it.
            follows: false

            # Further links, each with the keys above (role, listen-port or host-url, secret):
            # peers:
            #   hub:
            #     role: guest
            #     host-url: http://172.18.0.1:25596
            #     secret: ""
            """;

    /**
     * The role as written, lower case. YAML reads an unquoted {@code off} (and {@code no}) as false — the default file
     * itself says {@code role: off} — so false is off too.
     */
    static String role(org.bukkit.configuration.ConfigurationSection yaml) {
        String role = yaml.getString("role", "off").trim().toLowerCase(Locale.ROOT);
        return role.equals("false") || role.equals("no") ? "off" : role;
    }

    private NetworkLink() {
    }

    /** Whether {@code file} says this server shows the other one's player data rather than keeping its own. */
    public static boolean follows(Path file) {
        if (!Files.exists(file)) {
            return false;
        }
        YamlStore store = new YamlStore(file);
        YamlConfiguration yaml = store.read();
        return store.problems().isEmpty() && yaml.getBoolean("follows", false);
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
        return startEnd(yaml, "Server link", log);
    }

    /**
     * Further links, each under {@code peers.<name>} with the same keys as the main one — a survival server talking to
     * an arena and to a hub. Each is its own end; a peer that cannot start is left out with a log line.
     */
    public static java.util.Map<String, ServerLink> peers(Path file, LogChannel log) {
        java.util.Map<String, ServerLink> started = new java.util.LinkedHashMap<>();
        if (!Files.exists(file)) {
            return started;
        }
        YamlStore store = new YamlStore(file);
        YamlConfiguration yaml = store.read();
        org.bukkit.configuration.ConfigurationSection peers = yaml.getConfigurationSection("peers");
        if (!store.problems().isEmpty() || peers == null) {
            return started;
        }
        for (String name : peers.getKeys(false)) {
            org.bukkit.configuration.ConfigurationSection peer = peers.getConfigurationSection(name);
            if (peer != null) {
                startEnd(peer, "Server link '" + name + "'", log).ifPresent(end -> started.put(name, end));
            }
        }
        return started;
    }

    /** What this server calls itself to the others ({@code server-name}); empty when not set. */
    public static String serverName(Path file) {
        if (!Files.exists(file)) {
            return "";
        }
        YamlStore store = new YamlStore(file);
        YamlConfiguration yaml = store.read();
        return store.problems().isEmpty() ? yaml.getString("server-name", "").trim() : "";
    }

    private static Optional<ServerLink> startEnd(org.bukkit.configuration.ConfigurationSection yaml, String label,
                                                 LogChannel log) {
        String role = role(yaml);
        if (role.equals("off")) {
            return Optional.empty();
        }
        String secret = yaml.getString("secret", "");
        if (secret == null || secret.length() < LinkAuth.SHORTEST_SECRET) {
            log.error("{} is set to '{}' but its secret is shorter than {} characters; it is not started.",
                    label, role, LinkAuth.SHORTEST_SECRET);
            return Optional.empty();
        }
        try {
            return switch (role) {
                case "host" -> {
                    LinkHost host = LinkHost.listen(yaml.getString("listen-address", "0.0.0.0"),
                            yaml.getInt("listen-port", 25594), secret, POLL_WAIT);
                    log.info("{}: listening on port {}.", label, host.port());
                    yield Optional.of(host);
                }
                case "guest" -> {
                    URI url = URI.create(yaml.getString("host-url", ""));
                    log.info("{}: connecting to {}.", label, url);
                    yield Optional.of(LinkGuest.connect(url, secret, POLL_WAIT, RETRY));
                }
                default -> {
                    log.error("{}: the role '{}' is none of off, host, guest.", label, role);
                    yield Optional.empty();
                }
            };
        } catch (IOException | IllegalArgumentException failure) {
            log.error("{} could not start: {}", label, failure.getMessage());
            return Optional.empty();
        }
    }
}
