package de.raindancer.core.platform.network;

import de.raindancer.core.data.sql.Dialect;
import de.raindancer.core.data.sql.PostgresSettings;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Who this server is on its network, and where the network keeps its data — {@code network.yml}.
 *
 * <p>The one file RainsCore reads its settings from in 2.0.0. Everything else lives in the database
 * this file points at; the way to that database cannot. Environment variables win over the file, so a
 * container can keep the password out of it.
 *
 * @param serverId the server's key on the network ({@code smp}, {@code arena}): unique among its
 *                 servers, every server-bound row in the central database carries it
 * @param group    servers that share settings meant for "servers like this one"
 * @param problems what was wrong with the file, for the startup log; never stops the server
 */
public record NetworkConfig(String serverId, String group, Dialect dialect, PostgresSettings postgres,
                            List<String> problems) {

    public static final String FILE = "network.yml";

    /** A server on its own, as every 1.x server was. */
    public static final NetworkConfig STANDALONE = new NetworkConfig("server", "default", Dialect.SQLITE,
            PostgresSettings.of("localhost", 5432, "rains", "rains", ""), List.of());
    private static final int LONGEST_ID = 48;

    public NetworkConfig {
        problems = List.copyOf(problems);
    }

    public boolean isNetwork() {
        return dialect == Dialect.POSTGRESQL;
    }

    /**
     * Reads {@code network.yml} from {@code folder}, writing it first when it is not there.
     *
     * @param fallbackId the server's id when neither the file, the environment nor an old link.yml name one
     */
    /**
     * What the proxy calls this server, where it differs from its id — Lilly's SMP is {@code smp} on the network and
     * {@code lillyxoxo} in velocity.toml. {@code server.proxy-name} in the file, or {@code RAINS_SERVER_PROXY}; else
     * the id.
     */
    public static String proxyName(Path folder, Map<String, String> env, String serverId) {
        String fromEnv = env.get("RAINS_SERVER_PROXY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.strip();
        }
        Path file = folder.resolve(FILE);
        if (java.nio.file.Files.isRegularFile(file)) {
            org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
            try {
                yaml.loadFromString(java.nio.file.Files.readString(file));
                String named = yaml.getString("server.proxy-name", "");
                if (!named.isBlank()) {
                    return named.strip();
                }
            } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException unreadable) {
                // the id it is, then; load() reports a broken file
            }
        }
        return serverId;
    }

    public static NetworkConfig load(Path folder, Map<String, String> env, Supplier<String> fallbackId) {
        Path file = folder.resolve(FILE);
        List<String> problems = new ArrayList<>();
        YamlConfiguration yaml = new YamlConfiguration();
        boolean existed = Files.exists(file);
        if (existed) {
            try {
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException | InvalidConfigurationException unreadable) {
                problems.add(FILE + " could not be read, so the defaults are used: " + firstLine(unreadable.getMessage()));
                yaml = new YamlConfiguration();
            }
        }

        String id = normaliseId(firstOf(env.get("RAINS_SERVER_ID"), yaml.getString("server.id")));
        if (id.isEmpty()) {
            id = normaliseId(nameFromLink(folder));
        }
        if (id.isEmpty()) {
            id = normaliseId(fallbackId.get());
        }
        if (id.isEmpty()) {
            id = "server";
        }
        String group = normaliseId(firstOf(env.get("RAINS_SERVER_GROUP"), yaml.getString("server.group")));
        if (group.isEmpty()) {
            group = "default";
        }

        String type = firstOf(env.get("RAINS_DB_TYPE"), yaml.getString("database.type", "sqlite"));
        Dialect dialect = Dialect.named(type);
        if (dialect == Dialect.SQLITE && !type.isBlank() && !type.trim().equalsIgnoreCase("sqlite")) {
            problems.add("database.type '" + type + "' is not sqlite or postgresql; sqlite is used.");
        }

        String pg = "database.postgresql.";
        PostgresSettings postgres = new PostgresSettings(
                firstOf(env.get("RAINS_DB_HOST"), yaml.getString(pg + "host", "localhost")),
                parsePort(firstOf(env.get("RAINS_DB_PORT"), String.valueOf(yaml.getInt(pg + "port", 5432))), problems),
                firstOf(env.get("RAINS_DB_NAME"), yaml.getString(pg + "database", "rains")),
                firstOf(env.get("RAINS_DB_USER"), yaml.getString(pg + "user", "rains")),
                firstOf(env.get("RAINS_DB_PASSWORD"), yaml.getString(pg + "password", "")),
                yaml.getInt(pg + "pool-size", PostgresSettings.DEFAULT_POOL_SIZE),
                "RainsCore:" + id,
                yaml.getString(pg + "ssl", "prefer"));

        NetworkConfig config = new NetworkConfig(id, group, dialect, postgres, problems);
        if (!existed) {
            try {
                Files.createDirectories(folder);
                Files.writeString(file, config.render(), StandardCharsets.UTF_8);
            } catch (IOException unwritable) {
                problems.add(FILE + " could not be written: " + unwritable.getMessage());
            }
        }
        return config;
    }

    /** Lower case, letters, digits and _; at most 48 characters; blank when nothing usable is left. */
    public static String normaliseId(String raw) {
        if (raw == null) {
            return "";
        }
        String id = raw.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return id.length() > LONGEST_ID ? id.substring(0, LONGEST_ID) : id;
    }

    /** The name a 1.x server's link.yml gave it, which the other servers already know it by. */
    private static String nameFromLink(Path folder) {
        Path link = folder.resolve("link.yml");
        if (!Files.exists(link)) {
            return "";
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(link, StandardCharsets.UTF_8));
            return yaml.getString("server-name", "");
        } catch (IOException | InvalidConfigurationException unreadable) {
            return "";
        }
    }

    private static int parsePort(String raw, List<String> problems) {
        try {
            int port = Integer.parseInt(raw.trim());
            if (port > 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        problems.add("database.postgresql.port '" + raw + "' is not a port; 5432 is used.");
        return 5432;
    }

    private static String firstOf(String preferred, String otherwise) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return otherwise == null ? "" : otherwise;
    }

    private static String firstLine(String text) {
        return text == null ? "" : text.lines().findFirst().orElse("");
    }

    private String render() {
        return """
                # RainsCore — who this server is on its network, and where the network keeps its data.
                #
                # The only settings RainsCore keeps in a file. Everything else — settings, messages, players,
                # punishments, homes — lives in the database below. Environment variables win over this file:
                # RAINS_SERVER_ID, RAINS_SERVER_GROUP, RAINS_DB_TYPE, RAINS_DB_HOST, RAINS_DB_PORT, RAINS_DB_NAME,
                # RAINS_DB_USER, RAINS_DB_PASSWORD.

                server:
                  # This server's name on the network: smp, arena, hub … Unique among the network's servers;
                  # everything that belongs to this server alone is kept under it. Do not change it later.
                  id: %s
                  # Servers that share settings meant for "servers like this one" (all survival servers, all lobbies).
                  group: %s

                database:
                  # sqlite     — a server on its own: its data in .db files in this folder.
                  # postgresql — a network: every server of the network points at the same database below.
                  type: %s
                  postgresql:
                    host: %s
                    port: %d
                    database: %s
                    user: %s
                    password: ""
                    # Connections this server keeps at most. Every server has its own, so the database's
                    # max_connections must cover all of them together.
                    pool-size: %d
                    # disable, prefer, require, verify-ca, verify-full
                    ssl: %s
                """.formatted(serverId, group, dialect == Dialect.POSTGRESQL ? "postgresql" : "sqlite",
                postgres.host(), postgres.port(), postgres.database(), postgres.user(), postgres.poolSize(),
                postgres.sslMode());
    }

    @Override
    public String toString() {
        return "NetworkConfig[" + serverId + " in " + group + ", " + dialect
                + (isNetwork() ? " at " + postgres.describe() : "") + "]";
    }
}
