package de.raindancer.core.data.settings;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings sorted by plugin: a door per plugin on the front page, each plugin's own sections behind it, and the
 * things that make a setting findable and readable — what changed, what a typed word means, the icon and the lore.
 */
class SettingsFrontPageTest {

    @Settings(id = "walls", topics = {
            @Topic(path = "walls", title = "Walls", icon = Material.STONE_BRICKS),
            @Topic(path = "walls/gates", title = "Gates")})
    record Walls(@In("walls") @Title("Wall height") @Range(min = 1, max = 10) int wallHeight,
                 @In("walls/gates") @Title("Gates open") boolean gatesOpen,
                 @In("walls") @Title("Wall block") Material wallBlock) {
        static final Walls DEFAULTS = new Walls(3, true, Material.STONE);
    }

    /** A second record of the same plugin, by the id's prefix: walls-extra is still Walls. */
    @Settings(id = "walls-extra", topics = @Topic(path = "walls-extra", title = "Wall extras"))
    record WallExtras(@In("walls-extra") @Title("Moss") boolean moss) {
        static final WallExtras DEFAULTS = new WallExtras(false);
    }

    @Settings(id = "enchants", topics = {
            @Topic(path = "enchants", title = "Enchantments", icon = Material.ENCHANTED_BOOK),
            @Topic(path = "enchants/shards", title = "Shards")})
    record Enchants(@In("enchants") @Title("Flight seconds") @Range(min = 1, max = 3600)
                    @Describe("How many seconds of flight each level of the Flight enchantment holds, per charge.")
                    int flightSeconds,
                    @In("enchants") @Title("Switched off") @Choices(value = SettingsChoicesTest.Worlds.class,
                            listedMeansOff = true) List<String> switchedOff,
                    @In("enchants/shards") @Title("Shard chance") @Range(min = 0, max = 100) int shardChance) {
        static final Enchants DEFAULTS = new Enchants(30, List.of("world_the_end"), 100);
    }

    /** No topic named after itself: the door is named after the id. */
    @Settings(id = "claims", topics = {
            @Topic(path = "management", title = "Claims", icon = Material.GOLDEN_SHOVEL),
            @Topic(path = "appearance", title = "How claims look")})
    record Claims(@In("management") @Title("Claim limit") @Range(min = 1, max = 50) int claimLimit,
                  @In("appearance") @Title("Fence style") Style fenceStyle) {
        static final Claims DEFAULTS = new Claims(5, Style.SOLID);
    }

    enum Style { SOLID, DASHED }

    @Settings(id = "core", name = "Server basics", icon = Material.COMMAND_BLOCK, topics = {
            @Topic(path = "config", title = "Server settings"),
            @Topic(path = "appearance", title = "Appearance")})
    record Core(@In("config") @Title("Server name") String serverName,
                @In("appearance") @Title("Accent") net.kyori.adventure.text.format.NamedTextColor accent) {
        static final Core DEFAULTS = new Core("Lilly", net.kyori.adventure.text.format.NamedTextColor.RED);
    }

    @TempDir
    Path directory;
    private SettingsRegistry registry;
    private SettingsNavigation navigation;

    private <T> void add(Class<T> type, T defaults, String file) {
        SettingsStore<T> store = new SettingsStore<>(SettingsSchema.of(type, defaults), directory.resolve(file));
        store.load();
        registry.add(store);
    }

    @BeforeEach
    void setUp() {
        registry = new SettingsRegistry();
        add(Walls.class, Walls.DEFAULTS, "walls.yml");
        add(WallExtras.class, WallExtras.DEFAULTS, "walls-extra.yml");
        add(Enchants.class, Enchants.DEFAULTS, "enchants.yml");
        add(Claims.class, Claims.DEFAULTS, "claims.yml");
        add(Core.class, Core.DEFAULTS, "core.yml");
        navigation = new SettingsNavigation(registry);
    }

    private Setting<?> setting(String key) {
        return registry.setting(key).orElseThrow();
    }

    @Test
    @DisplayName("the front page is a door per plugin, by name — never one plugin's category mixed with another's")
    void doorPerPlugin() {
        SettingsPage root = navigation.page(null);

        assertThat(root.isRoot()).isTrue();
        assertThat(root.subtopics()).extracting(SettingsTopic::title)
                .containsExactly("Claims", "Enchantments", "Server basics", "Walls");
        assertThat(root.subtopics()).extracting(root::pathTo)
                .containsExactly("claims:", "enchants:", "core:", "walls:");
        assertThat(root.subtopics()).extracting(SettingsTopic::icon)
                .containsExactly(Material.GOLDEN_SHOVEL, Material.ENCHANTED_BOOK, Material.COMMAND_BLOCK,
                        Material.STONE_BRICKS);
        assertThat(root.settings()).isEmpty();
        assertThat(navigation.plugins()).extracting(SettingsNavigation.Plugin::id)
                .containsExactly("claims", "enchants", "core", "walls");
        String enchants = String.join("\n", navigation.describe(root.subtopics().get(1)));
        assertThat(enchants).as("not its own name again: what is one level in").contains("Inside: <white>Shards")
                .contains("3 settings");
    }

    @Test
    @DisplayName("a plugin with one section opens straight into it, and Back goes to the front page")
    void oneSectionOpensStraightIn() {
        SettingsPage enchants = navigation.page("enchants:");

        assertThat(enchants.title()).isEqualTo("Enchantments");
        assertThat(enchants.settings()).extracting(Setting::key).containsExactly("flight-seconds", "switched-off");
        assertThat(enchants.subtopics()).extracting(SettingsTopic::title).containsExactly("Shards");
        assertThat(enchants.subtopics()).extracting(enchants::pathTo).containsExactly("enchants:enchants/shards");
        assertThat(enchants.parentPath()).isNull();
        assertThat(navigation.page("enchants:enchants/shards").parentPath()).isEqualTo("enchants:");
        assertThat(navigation.page("enchants:enchants/shards").trail()).containsExactly("Enchantments", "Shards");
    }

    @Test
    @DisplayName("a plugin with several sections, from several records of its own, shows them all and only them")
    void severalSections() {
        SettingsPage walls = navigation.page("walls:");

        assertThat(walls.title()).isEqualTo("Walls");
        assertThat(walls.subtopics()).extracting(SettingsTopic::title).containsExactly("Walls", "Wall extras");
        assertThat(walls.settings()).isEmpty();
        assertThat(navigation.page("walls:walls").parentPath()).isEqualTo("walls:");
        assertThat(navigation.page("walls:walls/gates").parentPath()).isEqualTo("walls:walls");
        assertThat(navigation.page("walls:walls/gates").trail()).containsExactly("Walls", "Gates");
        assertThat(navigation.page("claims:").subtopics()).extracting(SettingsTopic::title)
                .containsExactly("Claims", "How claims look");
        assertThat(navigation.page("nonsense:").isRoot()).as("somewhere sensible").isTrue();
    }

    @Test
    @DisplayName("what was changed from the defaults is listed, and only that")
    void changed() {
        assertThat(navigation.changed()).isEmpty();
        registry.set("wall-height", "7");
        registry.set("moss", "true");

        assertThat(navigation.changed()).extracting(Setting::key).containsExactly("wall-height", "moss");
        assertThat(navigation.isChanged(setting("wall-height"))).isTrue();
        assertThat(navigation.isChanged(setting("gates-open"))).isFalse();
        assertThat(navigation.page(SettingsNavigation.CHANGED).settings()).extracting(Setting::key)
                .containsExactly("wall-height", "moss");
    }

    @Test
    @DisplayName("a typed word is a plugin, a setting, or neither — then with what it was probably meant to be")
    void words() {
        assertThat(navigation.resolve("enchants").kind()).isEqualTo(SettingsNavigation.Word.PLUGIN);
        assertThat(navigation.resolve("Enchantments").target()).as("by its name too").isEqualTo("enchants");
        assertThat(navigation.resolve("wall-height").kind()).isEqualTo(SettingsNavigation.Word.SETTING);
        SettingsNavigation.Resolved typo = navigation.resolve("enchnats");
        assertThat(typo.kind()).isEqualTo(SettingsNavigation.Word.UNKNOWN);
        assertThat(typo.closest()).contains("enchants");
    }

    @Test
    @DisplayName("an icon says what kind of setting it is: a flag's dye, the block itself, a colour's wool, a count")
    void icons() {
        assertThat(navigation.iconFor(setting("gates-open"))).isEqualTo(Material.LIME_DYE);
        assertThat(navigation.iconFor(setting("wall-block"))).isEqualTo(Material.STONE);
        assertThat(navigation.iconFor(setting("accent"))).isEqualTo(Material.RED_WOOL);
        assertThat(navigation.iconFor(setting("fence-style"))).isEqualTo(Material.OAK_SIGN);
        assertThat(navigation.iconFor(setting("switched-off"))).isEqualTo(Material.CHEST);
        assertThat(navigation.iconFor(setting("server-name"))).isEqualTo(Material.NAME_TAG);
        assertThat(navigation.iconFor(setting("wall-height"))).isEqualTo(Material.COMPARATOR);
        assertThat(navigation.countFor(setting("wall-height"))).as("3 shows as a stack of 3").isEqualTo(3);
        assertThat(navigation.countFor(setting("flight-seconds"))).isEqualTo(30);
        registry.set("flight-seconds", "600");
        assertThat(navigation.countFor(setting("flight-seconds"))).as("past a stack: just one").isEqualTo(1);
    }

    @Test
    @DisplayName("the lore wraps, shows the default only once it differs, names the command and the right click")
    void lore() {
        List<String> plain = navigation.describe(setting("flight-seconds"));
        assertThat(plain).allSatisfy(line -> assertThat(stripped(line).length()).isLessThanOrEqualTo(42));
        assertThat(String.join("\n", plain)).contains("flight-seconds").doesNotContain("Default");

        registry.set("flight-seconds", "60");
        String changed = String.join("\n", navigation.describe(setting("flight-seconds")));
        assertThat(changed).contains("Default: <white>30").contains("Right-click");

        String toggles = String.join("\n", navigation.describe(setting("switched-off")));
        assertThat(toggles).contains("World the end").doesNotContain("One of").doesNotContain("world_nether");
    }

    @Test
    @DisplayName("words are wrapped at spaces, never cut inside one")
    void wrapping() {
        assertThat(SettingsNavigation.wrap("one two three four five", 9))
                .containsExactly("one two", "three", "four five");
        assertThat(SettingsNavigation.wrap("", 9)).isEmpty();
        assertThat(SettingsNavigation.wrap("unbreakableword", 4)).containsExactly("unbreakableword");
    }

    @Test
    @DisplayName("/settings help says what a setting's name is, how to find one, and names this server's plugins")
    void help() {
        List<SettingsCommand.HelpLine> lines = SettingsCommand.help(navigation.plugins(), false);
        String all = String.join("\n", lines.stream().map(line -> line.command() + " " + line.what()).toList());

        assertThat(all).contains("/settings search").contains("/settings changed").contains("/settings set")
                .contains("/settings <plugin>").doesNotContain("sethere");
        assertThat(lines).allSatisfy(line -> assertThat((line.command() + " · " + line.what()).length())
                .as("one line of chat").isLessThanOrEqualTo(44));
        assertThat(SettingsCommand.pluginsLine(navigation.plugins())).isEqualTo("claims, enchants, core, walls");
        assertThat(SettingsCommand.help(navigation.plugins(), true).stream().map(SettingsCommand.HelpLine::command))
                .anyMatch(command -> command.contains("sethere"));
    }

    private static String stripped(String miniMessage) {
        return miniMessage.replaceAll("<[^>]+>", "");
    }

    @Test
    @DisplayName("a value that is one long word (an address, a key) is drawn whole on its own line, never cut")
    void longWordValue() {
        String address = "https://packs.example.org/lilly/resourcepack-2026-10-18-final.zip";
        registry.set("server-name", address);

        List<String> lore = navigation.describe(setting("server-name"));

        assertThat(lore).anySatisfy(line -> assertThat(line).startsWith("<gray>Now: "));
        assertThat(String.join("", lore)).contains(address);
        String door = String.join("\n", navigation.describe(navigation.page(null).subtopics().get(2)));
        assertThat(door).contains("Inside: ");
    }
}
