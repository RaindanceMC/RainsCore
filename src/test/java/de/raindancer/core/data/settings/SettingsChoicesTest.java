package de.raindancer.core.data.settings;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A list whose entries are named in advance: a page of on/off buttons, one per entry, instead of typing. */
class SettingsChoicesTest {

    public static final class Worlds implements Supplier<List<String>> {
        @Override
        public List<String> get() {
            return List.of("world", "world_nether", "world_the_end");
        }
    }

    @Settings(id = "travel", topics = @Topic(path = "travel", title = "Travel", icon = Material.COMPASS))
    record Travel(
            @In("travel") @Title("Closed worlds") @Choices(value = Worlds.class, listedMeansOff = true)
            List<String> closedWorlds,
            @In("travel") @Title("Greetings") List<String> greetings) {

        static final Travel DEFAULTS = new Travel(List.of("world_the_end"), List.of("hi"));
    }

    @TempDir
    Path directory;
    private SettingsRegistry registry;
    private SettingsNavigation navigation;

    @BeforeEach
    void setUp() {
        SettingsStore<Travel> store = new SettingsStore<>(SettingsSchema.of(Travel.class, Travel.DEFAULTS),
                directory.resolve("travel.yml"));
        store.load();
        registry = new SettingsRegistry();
        registry.add(store);
        navigation = new SettingsNavigation(registry);
    }

    private Setting<?> setting(String key) {
        return registry.setting(key).orElseThrow();
    }

    @Test
    @DisplayName("the schema knows every entry the list may hold, and which way round a listed one reads")
    void choicesAreRead() {
        assertThat(setting("closed-worlds").choices()).containsExactly("world", "world_nether", "world_the_end");
        assertThat(setting("closed-worlds").listedMeansOff()).isTrue();
        assertThat(setting("greetings").choices()).isEmpty();
        assertThat(setting("greetings").listedMeansOff()).isFalse();
    }

    @Test
    @DisplayName("clicking it opens a button per entry; a list without named entries is still typed")
    void clickOpensToggles() {
        assertThat(navigation.click("closed-worlds")).isEqualTo(SettingsNavigation.Click.NEEDS_TOGGLES);
        assertThat(navigation.hasOptions(setting("closed-worlds"))).as("not a pick of one").isFalse();
        assertThat(navigation.click("greetings")).isEqualTo(SettingsNavigation.Click.NEEDS_TYPING);
    }

    @Test
    @DisplayName("a button puts its entry in the list or takes it out, keeping the rest in their order")
    void toggling() {
        assertThat(SettingsNavigation.toggled(List.of("world_the_end"), "world"))
                .containsExactly("world_the_end", "world");
        assertThat(SettingsNavigation.toggled(List.of("world_the_end", "world"), "world_the_end"))
                .containsExactly("world");
        assertThat(SettingsNavigation.toggled(List.of(), "world")).containsExactly("world");

        navigation.toggle("closed-worlds", "world");
        assertThat(registry.display("closed-worlds")).isEqualTo("world_the_end, world");
        navigation.toggle("closed-worlds", "world_the_end");
        navigation.toggle("closed-worlds", "world");
        assertThat(registry.display("closed-worlds")).as("all taken out is an empty list").isEmpty();
    }

    @Settings(id = "broken", topics = @Topic(path = "x", title = "X", icon = Material.STONE))
    record Broken(@In("x") @Choices(Worlds.class) int notAList) {
        static final Broken DEFAULTS = new Broken(1);
    }

    @Test
    @DisplayName("@Choices on anything but a list is refused when the schema is built, not ignored")
    void onlyOnLists() {
        assertThatThrownBy(() -> SettingsSchema.of(Broken.class, Broken.DEFAULTS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("@Choices");
    }
}
