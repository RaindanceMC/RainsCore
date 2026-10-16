package de.raindancer.core.platform.network;

import de.raindancer.core.data.sql.Dialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkConfigTest {

    @TempDir
    Path folder;

    private NetworkConfig load(Map<String, String> env) {
        return NetworkConfig.load(folder, env, () -> "fallback");
    }

    @Test
    @DisplayName("a server without network.yml gets one written: SQLite, as every 1.x server had")
    void writtenOnFirstStart() throws IOException {
        NetworkConfig config = load(Map.of());
        assertThat(config.dialect()).isEqualTo(Dialect.SQLITE);
        assertThat(config.serverId()).isEqualTo("fallback");
        assertThat(config.group()).isEqualTo("default");
        Path file = folder.resolve("network.yml");
        assertThat(file).exists();
        String written = Files.readString(file);
        assertThat(written).contains("type: sqlite").contains("postgresql:").contains("pool-size: 10")
                .contains("# ");
        assertThat(load(Map.of())).isEqualTo(config);
    }

    @Test
    @DisplayName("an upgrading server keeps the name its link already gave it")
    void nameFromLinkYml() throws IOException {
        Files.writeString(folder.resolve("link.yml"), "role: guest\nserver-name: \"SMP\"\nsecret: x\n");
        NetworkConfig config = load(Map.of());
        assertThat(config.serverId()).isEqualTo("smp");
        assertThat(Files.readString(folder.resolve("network.yml"))).contains("id: smp");
    }

    @Test
    @DisplayName("the file is read: postgres with everything set")
    void readsPostgres() throws IOException {
        Files.writeString(folder.resolve("network.yml"), """
                server:
                  id: arena
                  group: games
                database:
                  type: postgresql
                  postgresql:
                    host: db.internal
                    port: 6543
                    database: lilly
                    user: rains
                    password: "s3cret: with colon"
                    pool-size: 7
                    ssl: require
                """);
        NetworkConfig config = load(Map.of());
        assertThat(config.dialect()).isEqualTo(Dialect.POSTGRESQL);
        assertThat(config.serverId()).isEqualTo("arena");
        assertThat(config.group()).isEqualTo("games");
        assertThat(config.postgres().host()).isEqualTo("db.internal");
        assertThat(config.postgres().port()).isEqualTo(6543);
        assertThat(config.postgres().database()).isEqualTo("lilly");
        assertThat(config.postgres().user()).isEqualTo("rains");
        assertThat(config.postgres().password()).isEqualTo("s3cret: with colon");
        assertThat(config.postgres().poolSize()).isEqualTo(7);
        assertThat(config.postgres().sslMode()).isEqualTo("require");
        assertThat(config.postgres().applicationName()).isEqualTo("RainsCore:arena");
    }

    @Test
    @DisplayName("environment variables win over the file, for containers that keep secrets out of files")
    void environmentWins() throws IOException {
        Files.writeString(folder.resolve("network.yml"), "server:\n  id: smp\ndatabase:\n  type: sqlite\n");
        NetworkConfig config = load(Map.of(
                "RAINS_DB_TYPE", "postgresql",
                "RAINS_DB_HOST", "pg",
                "RAINS_DB_PORT", "5433",
                "RAINS_DB_NAME", "net",
                "RAINS_DB_USER", "u",
                "RAINS_DB_PASSWORD", "p",
                "RAINS_SERVER_ID", "Hub 1",
                "RAINS_SERVER_GROUP", "Lobbies"));
        assertThat(config.dialect()).isEqualTo(Dialect.POSTGRESQL);
        assertThat(config.postgres().host()).isEqualTo("pg");
        assertThat(config.postgres().port()).isEqualTo(5433);
        assertThat(config.postgres().database()).isEqualTo("net");
        assertThat(config.postgres().user()).isEqualTo("u");
        assertThat(config.postgres().password()).isEqualTo("p");
        assertThat(config.serverId()).isEqualTo("hub_1");
        assertThat(config.group()).isEqualTo("lobbies");
    }

    @Test
    @DisplayName("ids are made safe to key rows by: lower case, letters, digits and _ only, never blank")
    void idsAreNormalised() {
        assertThat(NetworkConfig.normaliseId("  Lilly's SMP #2 ")).isEqualTo("lilly_s_smp_2");
        assertThat(NetworkConfig.normaliseId("")).isEmpty();
        assertThat(NetworkConfig.normaliseId(null)).isEmpty();
        assertThat(NetworkConfig.normaliseId("___")).isEmpty();
        assertThat(NetworkConfig.normaliseId("x".repeat(80))).hasSize(48);
    }

    @Test
    @DisplayName("nonsense in the file falls back to defaults rather than stopping the server")
    void nonsense() throws IOException {
        Files.writeString(folder.resolve("network.yml"), "server: [unclosed\n  : :\n");
        NetworkConfig config = load(Map.of());
        assertThat(config.dialect()).isEqualTo(Dialect.SQLITE);
        assertThat(config.serverId()).isEqualTo("fallback");
        assertThat(config.problems()).isNotEmpty();
    }

    @Test
    @DisplayName("an unknown database type is reported and treated as sqlite")
    void unknownType() throws IOException {
        Files.writeString(folder.resolve("network.yml"), "database:\n  type: oracle\n");
        NetworkConfig config = load(Map.of());
        assertThat(config.dialect()).isEqualTo(Dialect.SQLITE);
        assertThat(config.problems()).anyMatch(problem -> problem.contains("oracle"));
    }

    @Test
    @DisplayName("the password never shows in toString")
    void passwordHidden() throws IOException {
        Files.writeString(folder.resolve("network.yml"),
                "database:\n  type: postgresql\n  postgresql:\n    password: hunter2\n");
        assertThat(load(Map.of()).toString()).doesNotContain("hunter2");
    }

    @org.junit.jupiter.api.Test
    void theProxyMayKnowAServerByAnotherName() throws Exception {
        java.nio.file.Files.writeString(folder.resolve(NetworkConfig.FILE), "server:\n  id: smp\n  proxy-name: lillyxoxo\n");
        org.junit.jupiter.api.Assertions.assertEquals("lillyxoxo", NetworkConfig.proxyName(folder, java.util.Map.of(), "smp"));
        org.junit.jupiter.api.Assertions.assertEquals("other",
                NetworkConfig.proxyName(folder, java.util.Map.of("RAINS_SERVER_PROXY", "other"), "smp"));
        java.nio.file.Files.writeString(folder.resolve(NetworkConfig.FILE), "server:\n  id: arena\n");
        org.junit.jupiter.api.Assertions.assertEquals("arena", NetworkConfig.proxyName(folder, java.util.Map.of(), "arena"),
                "without one, the proxy calls it by its id");
    }
}
