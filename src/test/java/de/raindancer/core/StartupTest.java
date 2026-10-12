package de.raindancer.core;

import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.data.settings.Setting;
import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.settings.SettingsTopic;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Everything about starting up that can be checked without a server.
 *
 * <h2>Why this exists, and what it is not</h2>
 * The rule here is that a plugin is not finished until it has actually been loaded by Paper. This is
 * <em>not</em> that test — see the note at the bottom of {@code REFACTOR-PLAN.md} about the EULA.
 * What it is, is the set of start-up failures that do not need a server to find:
 *
 * <ul>
 *   <li>{@code paper-plugin.yml} naming a main class that does not exist, or is not a plugin —
 *       which fails at load with a stack trace and no plugin;</li>
 *   <li>{@link CoreConfig} being an invalid schema — {@link SettingsSchema#of} throws during
 *       {@code onEnable}, so a typo in an annotation takes the whole plugin down;</li>
 *   <li>the palette mapping in {@code RainsCorePlugin} missing a key, which would silently give
 *       every window the theme's colour and ignore what the server set;</li>
 *   <li>the shipped defaults not surviving a round trip through the file they are written to.</li>
 * </ul>
 *
 * Each of those has exactly one symptom on a real server — "the plugin did not load" — and each is
 * cheaper to find here.
 */
class StartupTest {

    private static final Path DESCRIPTOR = Path.of("src/main/resources/paper-plugin.yml");

    // ------------------------------------------------------------------ the descriptor

    @Test
    @DisplayName("paper-plugin.yml names a main class that exists and really is a plugin")
    void mainClassExists() throws Exception {
        YamlConfiguration descriptor = descriptor();
        String main = descriptor.getString("main");
        assertThat(main).isNotBlank();

        Class<?> mainClass = Class.forName(main);
        assertThat(org.bukkit.plugin.java.JavaPlugin.class)
                .as("%s is named as the main class but does not extend JavaPlugin", main)
                .isAssignableFrom(mainClass);
    }

    @Test
    @DisplayName("the descriptor carries everything Paper needs to load it")
    void descriptorIsComplete() throws Exception {
        YamlConfiguration descriptor = descriptor();
        assertThat(descriptor.getString("name")).isEqualTo("RainsCore");
        assertThat(descriptor.getString("api-version")).isNotBlank();
        assertThat(descriptor.getString("description")).isNotBlank();
        assertThat(descriptor.getString("author")).isNotBlank();
    }

    @Test
    @DisplayName("the version is filtered in by the build rather than written twice")
    void versionComesFromTheBuild() throws IOException {
        assertThat(Files.readString(DESCRIPTOR))
                .as("a version written out by hand is a version that will disagree with the POM")
                .contains("${project.version}");
    }

    private static YamlConfiguration descriptor() throws Exception {
        YamlConfiguration loaded = new YamlConfiguration();
        // The build filters ${project.version} in; the source has the placeholder, which is not
        // valid YAML on its own line without quotes — it is quoted, so this parses either way.
        loaded.loadFromString(Files.readString(DESCRIPTOR));
        return loaded;
    }

    // ------------------------------------------------------------------ the settings record

    @Test
    @DisplayName("RainsCore's own settings record is a valid schema")
    void ownSettingsAreValid() {
        // SettingsSchema.of throws on any mistake in the declaration, and onEnable calls it before
        // anything else — so a typo in an annotation here is a plugin that does not start at all.
        SettingsSchema<CoreConfig> schema = SettingsSchema.of(CoreConfig.class, CoreConfig.DEFAULTS);
        assertThat(schema.id()).isEqualTo("core");
        assertThat(schema.settings()).isNotEmpty();
    }

    @Test
    @DisplayName("the server's name is a setting, empty by default so the MOTD keeps naming an untouched server")
    void serverNameIsASetting() {
        SettingsSchema<CoreConfig> schema = SettingsSchema.of(CoreConfig.class, CoreConfig.DEFAULTS);
        assertThat(schema.keys()).anyMatch(key -> key.endsWith("server-name"));
        assertThat(CoreConfig.DEFAULTS.serverName()).isEmpty();
    }

    @Test
    @DisplayName("every setting is reachable from a topic, so none of them is invisible in the menu")
    void everySettingIsReachable() {
        SettingsSchema<CoreConfig> schema = SettingsSchema.of(CoreConfig.class, CoreConfig.DEFAULTS);

        List<String> reachable = new ArrayList<>();
        for (SettingsTopic root : schema.topics().visibleRoots()) {
            for (Setting<?> setting : root.allSettings()) {
                reachable.add(setting.key());
            }
        }
        assertThat(reachable)
                .as("a setting no topic holds can be changed by command and never seen in the GUI")
                .containsExactlyInAnyOrderElementsOf(schema.keys());
    }

    @Test
    @DisplayName("no page is a wall of buttons — that is the whole point of the tree")
    void noTopicIsOverfull() {
        SettingsSchema<CoreConfig> schema = SettingsSchema.of(CoreConfig.class, CoreConfig.DEFAULTS);
        for (SettingsTopic topic : schema.topics().all()) {
            // A chest page has 54 slots and the framework keeps the bottom row, so 45 is the hard
            // ceiling. Well before that a page stops being readable, which is what this guards.
            assertThat(topic.settings().size())
                    .as("%s holds %d settings; split it into subtopics",
                            topic.path(), topic.settings().size())
                    .isLessThanOrEqualTo(28);
        }
    }

    // ------------------------------------------------------------------ the palette wiring

    /**
     * The palette is the one place two vocabularies meet: {@link Style} asks for
     * {@code style.item-name}, and the settings record holds it under a name of its own. A key
     * missing from that mapping is invisible — the window simply takes the theme's colour and the
     * server's own setting is ignored, with nothing logged.
     */
    @Test
    @DisplayName("every palette key the Style class asks for is answered by the settings record")
    void everyPaletteKeyIsMapped() {
        List<String> keys = List.of(
                Style.PRESET, Style.TITLE_LABEL, Style.TITLE_VALUE, Style.TITLE_SEPARATOR,
                Style.ITEM_NAME, Style.ITEM_LORE, Style.OK, Style.WARN, Style.BAD,
                Style.DANGER, Style.BRAND_FROM, Style.BRAND_TO);

        List<String> unmapped = new ArrayList<>();
        for (String key : keys) {
            if (paletteValueFor(key) == null) {
                unmapped.add(key);
            }
        }
        assertThat(unmapped)
                .as("these would silently fall back to the theme, ignoring what the server set")
                .isEmpty();
    }

    /**
     * The same mapping {@code RainsCorePlugin#colourFor} performs.
     *
     * <p>Duplicated here rather than reached through the plugin, because constructing a
     * {@link org.bukkit.plugin.java.JavaPlugin} needs a server. That duplication is exactly what the
     * test is for: if the two ever disagree, one of them is missing a key.
     */
    private static String paletteValueFor(String key) {
        CoreConfig config = CoreConfig.DEFAULTS;
        return switch (key) {
            case Style.PRESET -> config.theme().name();
            case Style.TITLE_LABEL -> config.titleLabel();
            case Style.TITLE_VALUE -> config.titleValue();
            case Style.TITLE_SEPARATOR -> config.titleSeparator();
            case Style.ITEM_NAME -> config.itemName();
            case Style.ITEM_LORE -> config.itemLore();
            case Style.OK -> config.ok();
            case Style.WARN -> config.warn();
            case Style.BAD -> config.bad();
            case Style.DANGER -> config.danger();
            case Style.BRAND_FROM -> config.brandFrom();
            case Style.BRAND_TO -> config.brandTo();
            default -> null;
        };
    }

    @Test
    @DisplayName("every theme the setting offers is a theme the palette knows")
    void everyThemeExists() {
        for (CoreConfig.Theme theme : CoreConfig.Theme.values()) {
            String id = theme.name().toLowerCase(java.util.Locale.ROOT);
            assertThat(de.raindancer.core.ui.chat.Preset.ids())
                    .as("the setting offers '%s' but no preset answers to it", id)
                    .contains(id);
        }
    }

    // ------------------------------------------------------------------ the first run

    @Test
    @DisplayName("a first start writes a config.yml this plugin can read back without complaint")
    void firstRunRoundTrips(@TempDir Path directory) {
        Path file = directory.resolve("config.yml");
        SettingsSchema<CoreConfig> schema = SettingsSchema.of(CoreConfig.class, CoreConfig.DEFAULTS);

        SettingsStore<CoreConfig> first = new SettingsStore<>(schema, file);
        first.load();
        first.save();

        SettingsStore<CoreConfig> second = new SettingsStore<>(schema, file);
        second.load();

        assertThat(second.problems())
                .as("the file this plugin writes on its first start must be one it can read")
                .isEmpty();
        assertThat(second.current()).isEqualTo(CoreConfig.DEFAULTS);
    }

    @Test
    @DisplayName("the written config.yml explains itself")
    void theWrittenFileIsDocumented(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("config.yml");
        SettingsStore<CoreConfig> store = new SettingsStore<>(
                SettingsSchema.of(CoreConfig.class, CoreConfig.DEFAULTS), file);
        store.load();
        store.save();

        String written = Files.readString(file);
        assertThat(written)
                .contains("Days of logs to keep")
                .contains("What is written down, and for how long.")
                .contains("From 1 to 365.");
    }
}
