package de.raindancer.core.data.settings;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.testkit.TestDatabases;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Settings on a network: the network's, a group's, a server's own — and the move there from 1.x files. */
@Tag("database")
class SettingsLayersTest {

    @Settings(id = "chat", topics = @Topic(path = "config/chat", title = "Chat", icon = Material.PAPER))
    record ChatConfig(
            @In("config/chat") @Title("Format") String format,
            @In("config/chat") @Title("Radius") @Range(min = 0, max = 1000) int radius,
            @In("config/chat") @Title("Filter") boolean filter) {
        static final ChatConfig DEFAULTS = new ChatConfig("<name>: <message>", 0, true);
    }

    private static final SettingsSchema<ChatConfig> SCHEMA = SettingsSchema.of(ChatConfig.class, ChatConfig.DEFAULTS);

    @TempDir
    Path plugins;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();

    /** One server: its own folder (for its 1.x file), its own documents, one database for all. */
    private Documents documents(String server, String scope) {
        Database database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        databases.add(database);
        return new Documents(database, hub.bus(server), plugins.resolve(server), scope, System::currentTimeMillis);
    }

    private SettingsStore<ChatConfig> store(Documents documents, String server, String group) {
        SettingsStore<ChatConfig> store = new SettingsStore<>(SCHEMA,
                plugins.resolve(server).resolve("RainsChat").resolve("config.yml"), documents, group);
        store.load();
        return store;
    }

    private void oldFile(String server, String text) throws Exception {
        Path file = plugins.resolve(server).resolve("RainsChat").resolve("config.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("the first server's 1.x config becomes the network's; nothing changes for it")
    void firstServerSharesItsSettings() throws Exception {
        oldFile("smp", "format: '[SMP] <name>: <message>'\nradius: 50\nfilter: false\n");
        SettingsStore<ChatConfig> smp = store(documents("smp", "smp"), "smp", "");
        assertThat(smp.isLayered()).isTrue();
        assertThat(smp.current()).isEqualTo(new ChatConfig("[SMP] <name>: <message>", 50, false));
        assertThat(smp.layerOf("radius")).isEqualTo(SettingsStore.Layer.NETWORK);

        SettingsStore<ChatConfig> arena = store(documents("arena", "arena"), "arena", "");
        assertThat(arena.current()).as("a server with no file of its own takes the network's")
                .isEqualTo(new ChatConfig("[SMP] <name>: <message>", 50, false));
    }

    @Test
    @DisplayName("a later server keeps only what it had set differently, as its own; its settings stay as they were")
    void laterServerKeepsItsDifferences() throws Exception {
        oldFile("smp", "format: '<name>: <message>'\nradius: 50\nfilter: true\n");
        store(documents("smp", "smp"), "smp", "");
        oldFile("arena", "format: '<name>: <message>'\nradius: 0\nfilter: true\n");

        SettingsStore<ChatConfig> arena = store(documents("arena", "arena"), "arena", "");

        assertThat(arena.current()).isEqualTo(new ChatConfig("<name>: <message>", 0, true));
        assertThat(arena.layerOf("radius")).isEqualTo(SettingsStore.Layer.SERVER);
        assertThat(arena.layerOf("format")).isEqualTo(SettingsStore.Layer.NETWORK);
    }

    @Test
    @DisplayName("a change to the network's value reaches every server that does not have its own")
    void networkChangeTravels() throws Exception {
        oldFile("smp", "radius: 50\n");
        SettingsStore<ChatConfig> smp = store(documents("smp", "smp"), "smp", "");
        SettingsStore<ChatConfig> arena = store(documents("arena", "arena"), "arena", "");
        List<ChatConfig> heard = new ArrayList<>();
        arena.onChange(heard::add);

        assertThat(smp.set("format", "<gold><name></gold>: <message>")).isTrue();
        smp.save();
        hub.drain();

        assertThat(arena.current().format()).isEqualTo("<gold><name></gold>: <message>");
        assertThat(heard).hasSize(1);
    }

    @Test
    @DisplayName("a server's own value survives a network change, and changing it changes only it")
    void ownValueStaysOwn() throws Exception {
        oldFile("smp", "radius: 50\n");
        SettingsStore<ChatConfig> smp = store(documents("smp", "smp"), "smp", "");
        SettingsStore<ChatConfig> arena = store(documents("arena", "arena"), "arena", "");
        assertThat(arena.set("radius", "5", SettingsStore.Layer.SERVER)).isTrue();
        arena.save();
        smp.set("radius", "80");
        smp.save();
        hub.drain();
        assertThat(arena.current().radius()).isEqualTo(5);

        assertThat(arena.set("radius", "7")).isTrue();
        arena.save();
        hub.drain();
        assertThat(arena.layerOf("radius")).isEqualTo(SettingsStore.Layer.SERVER);
        assertThat(smp.current().radius()).as("the network's value is untouched").isEqualTo(80);

        arena.clearOverride("radius", SettingsStore.Layer.SERVER);
        arena.save();
        assertThat(arena.current().radius()).isEqualTo(80);
    }

    @Test
    @DisplayName("a group's value applies to every server of the group, and to nobody else")
    void groups() throws Exception {
        oldFile("smp", "radius: 50\n");
        SettingsStore<ChatConfig> smp = store(documents("smp", "smp"), "smp", "survival");
        SettingsStore<ChatConfig> smp2 = store(documents("smp2", "smp2"), "smp2", "survival");
        SettingsStore<ChatConfig> hubServer = store(documents("hub", "hub"), "hub", "lobby");
        assertThat(smp.set("filter", "false", SettingsStore.Layer.GROUP)).isTrue();
        smp.save();
        hub.drain();
        assertThat(smp2.current().filter()).isFalse();
        assertThat(hubServer.current().filter()).isTrue();
    }

    @Test
    @DisplayName("a server of its own keeps one document, exactly as in 1.x")
    void standaloneIsNotLayered() throws Exception {
        oldFile("alone", "radius: 9\n");
        SettingsStore<ChatConfig> alone = store(documents("alone", ""), "alone", "");
        assertThat(alone.isLayered()).isFalse();
        assertThat(alone.current().radius()).isEqualTo(9);
        alone.set("radius", "10");
        assertThat(alone.trySave()).isTrue();
        assertThat(new YamlStore(plugins.resolve("alone/RainsChat/config.yml"), documents("alone", ""),
                de.raindancer.core.data.store.Documents.Scope.SERVER).read().getInt("radius")).isEqualTo(10);
    }

    @Test
    @DisplayName("once moved, a restart does not move again: the server's document is overrides only")
    void migratesOnce() throws Exception {
        oldFile("smp", "radius: 50\n");
        store(documents("smp", "smp"), "smp", "");
        oldFile("arena", "radius: 3\n");
        store(documents("arena", "arena"), "arena", "");
        SettingsStore<ChatConfig> again = store(documents("arena", "arena"), "arena", "");
        assertThat(again.current().radius()).isEqualTo(3);
        assertThat(again.layerOf("radius")).isEqualTo(SettingsStore.Layer.SERVER);
        assertThat(again.layerOf("format")).isEqualTo(SettingsStore.Layer.NETWORK);
    }

    @Test
    @DisplayName("rainscore.settings reaches this server only; the group's and the network's values need more")
    void reachNeedsPermission() throws Exception {
        oldFile("smp", "radius: 50\n");
        SettingsStore<ChatConfig> arena = store(documents("arena", "arena"), "arena", "survival");
        arena.set("filter", "false", SettingsStore.Layer.GROUP);
        arena.set("format", "x", SettingsStore.Layer.SERVER);
        SettingsRegistry registry = new SettingsRegistry();
        registry.add(arena);
        org.bukkit.permissions.Permissible admin = org.mockito.Mockito.mock(org.bukkit.permissions.Permissible.class);

        assertThat(registry.mayChange(admin, "radius")).as("the network's value").isFalse();
        assertThat(registry.mayChange(admin, "filter")).as("the group's value").isFalse();
        assertThat(registry.mayChange(admin, "format")).as("this server's own").isTrue();
        assertThat(registry.mayChangeAt(admin, "radius", SettingsStore.Layer.SERVER)).isTrue();

        org.mockito.Mockito.when(admin.hasPermission(SettingsRegistry.GROUP_PERMISSION)).thenReturn(true);
        assertThat(registry.mayChange(admin, "filter")).isTrue();
        assertThat(registry.mayChange(admin, "radius")).isFalse();
        org.mockito.Mockito.when(admin.hasPermission(SettingsRegistry.NETWORK_PERMISSION)).thenReturn(true);
        assertThat(registry.mayChange(admin, "radius")).isTrue();
        assertThat(registry.mayChange(null, "radius")).as("code and the console").isTrue();
    }

    @Test
    @DisplayName("a group value on a server without a group is refused, never written as the network's")
    void noGroupNoGroupValue() throws Exception {
        oldFile("smp", "radius: 50\n");
        store(documents("smp", "smp"), "smp", "");
        SettingsStore<ChatConfig> lone = store(documents("lone", "lone"), "lone", "");
        assertThat(lone.set("radius", "9", SettingsStore.Layer.GROUP)).isFalse();
        assertThat(lone.current().radius()).isEqualTo(50);
        assertThat(lone.layerOf("radius")).isEqualTo(SettingsStore.Layer.NETWORK);
    }
}
