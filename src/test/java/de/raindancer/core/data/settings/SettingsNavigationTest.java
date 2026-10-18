package de.raindancer.core.data.settings;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a settings screen shows, and where clicking takes you.
 *
 * <h2>Why this is a class of its own</h2>
 * The same reason {@code MenuLayout} is: a menu cannot be opened without a server, but every
 * decision worth getting right here is arithmetic over a tree — which page shows what, what the
 * trail at the top says, whether a click drills in or changes a value. Putting those in a class that
 * has never heard of an {@code ItemStack} means they are tested rather than clicked through.
 */
class SettingsNavigationTest {

    @Settings(id = "claims", topics = {
            @Topic(path = "config/limits", title = "Limits", icon = Material.BARRIER,
                    description = "How much one player may have."),
            @Topic(path = "config/limits/claims", title = "Claims", icon = Material.GRASS_BLOCK),
            @Topic(path = "management/fences", title = "Fences", icon = Material.OAK_FENCE),
    })
    record ClaimConfig(
            @In("config/limits") @Title("Blocks per player") @Range(min = 0, max = 100_000)
            int blocksPerPlayer,
            @In("config/limits/claims") @Title("Claims per player") @Range(min = 1, max = 20)
            int claimsPerPlayer,
            @In("management/fences") @Title("Show fences") boolean fencesEnabled,
            @In("management/fences") @Title("Fence style") Style fenceStyle,
            @In("management/fences") @Title("Fence block") Material fenceBlock,
            @In("config/limits") @Title("World seed") long worldSeed) {

        static final ClaimConfig DEFAULTS =
                new ClaimConfig(40_000, 5, true, Style.SOLID, Material.OAK_FENCE, 12345L);
    }

    enum Style { SOLID, DASHED, NONE }

    @TempDir
    Path directory;
    private SettingsRegistry registry;
    private SettingsNavigation navigation;

    @BeforeEach
    void setUp() {
        SettingsStore<ClaimConfig> store = new SettingsStore<>(
                SettingsSchema.of(ClaimConfig.class, ClaimConfig.DEFAULTS),
                directory.resolve("claims.yml"));
        store.load();
        registry = new SettingsRegistry();
        registry.add(store);
        navigation = new SettingsNavigation(registry);
    }

    // ------------------------------------------------------------------ what a page shows

    @Nested
    @DisplayName("a page")
    class Pages {

        @Test
        @DisplayName("at the root shows a door per plugin — here the one — and nothing else")
        void rootShowsCategories() {
            SettingsPage root = navigation.page(null);
            assertThat(root.isRoot()).isTrue();
            assertThat(root.subtopics()).extracting(root::pathTo).containsExactly("claims:");
            assertThat(navigation.page("claims:").subtopics()).extracting(SettingsTopic::path)
                    .containsExactlyInAnyOrder("config", "management");
            assertThat(root.settings()).isEmpty();
        }

        @Test
        @DisplayName("with subtopics and no settings of its own is a menu")
        void aMenuPage() {
            SettingsPage config = navigation.page("config");
            assertThat(config.isMenu()).isTrue();
            assertThat(config.subtopics()).extracting(SettingsTopic::path)
                    .containsExactly("config/limits");
            assertThat(config.settings()).isEmpty();
        }

        @Test
        @DisplayName("can hold both settings and a way further in")
        void aPageWithBoth() {
            SettingsPage limits = navigation.page("config/limits");
            assertThat(limits.settings()).extracting(Setting::key)
                    .containsExactly("blocks-per-player", "world-seed");
            assertThat(limits.subtopics()).extracting(SettingsTopic::path)
                    .containsExactly("config/limits/claims");
        }

        @Test
        @DisplayName("a page nobody declared is the root rather than an empty window")
        void unknownPathFallsBack() {
            assertThat(navigation.page("nothing/like/this").isRoot()).isTrue();
        }

        @Test
        @DisplayName("an empty category is not offered, so no button opens nothing")
        void hidesEmptyCategories() {
            assertThat(navigation.page(null).subtopics())
                    .allSatisfy(topic -> assertThat(topic.isEmpty()).isFalse());
        }
    }

    // ------------------------------------------------------------------ the trail

    @Nested
    @DisplayName("the trail at the top of the window")
    class Trail {

        @Test
        @DisplayName("names where you are, from the root inwards")
        void showsWhereYouAre() {
            assertThat(navigation.page("config/limits/claims").trail())
                    .containsExactly("Server settings", "Limits", "Claims");
        }

        @Test
        @DisplayName("is just the plugin's name at the root")
        void isEmptyAtTheRoot() {
            assertThat(navigation.page(null).trail()).isEmpty();
        }

        @Test
        @DisplayName("is shortened from the left when it would run off the window")
        void shortensADeepTrail() {
            List<String> full = List.of("Server settings", "Limits", "Claims", "Blocks", "Per world");
            assertThat(SettingsPage.shortenTrail(full, 3))
                    .as("the end is where you are; the start is context you can see by going back")
                    .containsExactly("…", "Claims", "Blocks", "Per world");
        }

        @Test
        @DisplayName("a trail that fits is left alone")
        void leavesAShortTrail() {
            List<String> full = List.of("Server settings", "Limits");
            assertThat(SettingsPage.shortenTrail(full, 3)).isEqualTo(full);
        }
    }

    // ------------------------------------------------------------------ pages of settings

    @Nested
    @DisplayName("a page with more settings than one window holds")
    class Paging {

        private SettingsPage pageOf(int settings, int topics) {
            Setting<?> one = navigation.page("management/fences").settings().getFirst();
            SettingsTopic topic = navigation.page(null).subtopics().getFirst();
            return new SettingsPage("x", "X", java.util.Collections.nCopies(topics, topic),
                    java.util.Collections.nCopies(settings, one), List.of());
        }

        @Test
        @DisplayName("shows every setting across its pages — none is dropped past the first window")
        void nothingIsDropped() {
            SettingsPage page = pageOf(38, 0);
            assertThat(page.pageCount(7, 18)).isEqualTo(3);
            assertThat(page.settingsOn(0, 18)).hasSize(18);
            assertThat(page.settingsOn(1, 18)).hasSize(18);
            assertThat(page.settingsOn(2, 18)).hasSize(2);
            assertThat(page.settingsOn(3, 18)).isEmpty();
        }

        @Test
        @DisplayName("pages as far as whichever of categories and settings runs longest")
        void longestDecides() {
            assertThat(pageOf(5, 15).pageCount(7, 18)).isEqualTo(3);
            assertThat(pageOf(19, 2).pageCount(7, 18)).isEqualTo(2);
            assertThat(pageOf(0, 0).pageCount(7, 18)).isEqualTo(1);
            assertThat(pageOf(5, 15).topicsOn(2, 7)).hasSize(1);
        }
    }

    // ------------------------------------------------------------------ going up

    @Nested
    @DisplayName("going back")
    class GoingUp {

        @Test
        @DisplayName("from a page goes to the one above it")
        void goesUpOne() {
            assertThat(navigation.page("config/limits/claims").parentPath()).isEqualTo("config/limits");
            assertThat(navigation.page("config/limits").parentPath()).isEqualTo("config");
        }

        @Test
        @DisplayName("from a top-level category goes to the root")
        void goesToTheRoot() {
            assertThat(navigation.page("config").parentPath()).isNull();
        }

        @Test
        @DisplayName("the root has nowhere above it")
        void theRootHasNoParent() {
            assertThat(navigation.page(null).parentPath()).isNull();
        }
    }

    // ------------------------------------------------------------------ clicking a setting

    @Nested
    @DisplayName("clicking a setting")
    class Clicking {

        @Test
        @DisplayName("a flag flips on the spot")
        void flagsToggle() {
            assertThat(navigation.canCycle(setting("fences-enabled"))).isTrue();
            assertThat(navigation.click("fences-enabled")).isEqualTo(SettingsNavigation.Click.CYCLED);
            assertThat(registry.display("fences-enabled")).isEqualTo("off");
        }

        @Test
        @DisplayName("a named choice opens the list of them rather than advancing by one")
        void choicesArePicked() {
            // Cycling meant reading the lore to find out where you had landed, and clicking past the
            // one you wanted meant going all the way round again. A list shows every answer at once.
            assertThat(navigation.click("fence-style"))
                    .isEqualTo(SettingsNavigation.Click.NEEDS_OPTION_CHOICE);
            assertThat(registry.display("fence-style"))
                    .as("clicking must not have changed it")
                    .isEqualTo("solid");
        }

        @Test
        @DisplayName("a number inside a range is picked, not typed")
        void rangedNumbersArePicked() {
            assertThat(navigation.canCycle(setting("blocks-per-player"))).isFalse();
            assertThat(navigation.click("blocks-per-player"))
                    .isEqualTo(SettingsNavigation.Click.NEEDS_NUMBER_CHOICE);
            assertThat(registry.display("blocks-per-player"))
                    .as("clicking must not have changed it")
                    .isEqualTo("40000");
        }

        @Test
        @DisplayName("a number with no range at all still has to be typed — there is nothing to show")
        void unboundedNumbersStillNeedTyping() {
            assertThat(navigation.click("world-seed"))
                    .isEqualTo(SettingsNavigation.Click.NEEDS_TYPING);
        }

        @Test
        @DisplayName("a block or item asks for Core's chooser, not a typed value")
        void materialsNeedTheChooser() {
            // The bug this guards against: a Material setting fell through to NEEDS_TYPING, edited by
            // typing a name into chat — which is how a server ended up with its lobby walls built out
            // of "gray_candle". A name that parses is not the same as a name somebody meant to type.
            assertThat(navigation.canCycle(setting("fence-block"))).isFalse();
            assertThat(navigation.click("fence-block"))
                    .isEqualTo(SettingsNavigation.Click.NEEDS_MATERIAL_CHOICE);
            assertThat(registry.display("fence-block"))
                    .as("clicking must not have changed it")
                    .isEqualTo("oak_fence");
        }

        @Test
        @DisplayName("a setting nobody knows does nothing")
        void unknownDoesNothing() {
            assertThat(navigation.click("nothing-like-this"))
                    .isEqualTo(SettingsNavigation.Click.UNKNOWN);
        }
    }

    // ------------------------------------------------------------------ what a button says

    @Nested
    @DisplayName("what a setting's button says")
    class Buttons {

        @Test
        @DisplayName("its title, its description, its value and how to change it")
        void describesTheSetting() {
            List<String> lore = navigation.describe(setting("blocks-per-player"));
            assertThat(lore).anySatisfy(line -> assertThat(line).contains("40000"));
            assertThat(lore).anySatisfy(line -> assertThat(line).contains("0"));
            assertThat(lore).anySatisfy(line -> assertThat(line).contains("100000"));
            assertThat(lore).anySatisfy(line -> assertThat(line).containsIgnoringCase("pick a number"));
        }

        @Test
        @DisplayName("a flag says what clicking does rather than what it is")
        void describesAFlag() {
            assertThat(navigation.describe(setting("fences-enabled")))
                    .anySatisfy(line -> assertThat(line).containsIgnoringCase("click"));
        }

        @Test
        @DisplayName("a choice lists what it can be")
        void listsChoices() {
            assertThat(navigation.describe(setting("fence-style")))
                    .anySatisfy(line -> assertThat(line).contains("dashed"));
        }

        @Test
        @DisplayName("only a free-text value is still described as something to type")
        void onlyTextSaysType() {
            assertThat(navigation.describe(setting("fence-style")))
                    .noneSatisfy(line -> assertThat(line).containsIgnoringCase("to type"));
            assertThat(navigation.describe(setting("world-seed")))
                    .anySatisfy(line -> assertThat(line).containsIgnoringCase("to type"));
        }

        @Test
        @DisplayName("a block or item says to choose rather than to type")
        void describesAMaterial() {
            assertThat(navigation.describe(setting("fence-block")))
                    .anySatisfy(line -> assertThat(line).containsIgnoringCase("choose"));
        }

        @Test
        @DisplayName("which plugin owns it, so a shared page is not confusing")
        void namesTheOwningPlugin() {
            assertThat(navigation.describe(setting("blocks-per-player")))
                    .anySatisfy(line -> assertThat(line).contains("Claims · blocks-per-player"));
        }
    }

    private Setting<?> setting(String key) {
        return registry.setting(key).orElseThrow();
    }

    // ------------------------------------------------------------------ finding

    @Nested
    @DisplayName("finding a setting")
    class Finding {

        @Test
        @DisplayName("search matches the name, the key, the description and the category, every word")
        void search() {
            assertThat(navigation.search("fence")).extracting(Setting::key)
                    .containsExactly("fences-enabled", "fence-style", "fence-block");
            assertThat(navigation.search("fence block")).extracting(Setting::key).containsExactly("fence-block");
            assertThat(navigation.search("how much")).extracting(Setting::key)
                    .as("by the category's description").contains("blocks-per-player", "world-seed");
            assertThat(navigation.search("   ")).isEmpty();
            assertThat(navigation.search("zebra")).isEmpty();
        }

        @Test
        @DisplayName("an exact key or title comes first")
        void ranking() {
            assertThat(navigation.search("claims per player")).extracting(Setting::key).first()
                    .isEqualTo("claims-per-player");
            assertThat(navigation.search("fence-block")).extracting(Setting::key).first().isEqualTo("fence-block");
        }

        @Test
        @DisplayName("a mistyped key gets the nearest names, best first, and nothing silly")
        void didYouMean() {
            assertThat(navigation.closest("fenceStyel", 3)).first().isEqualTo("fence-style");
            assertThat(navigation.closest("blocks", 3)).contains("blocks-per-player");
            assertThat(navigation.closest("fence_block", 1)).as("however it is joined").containsExactly("fence-block");
            assertThat(navigation.closest("xyzzy", 3)).isEmpty();
            assertThat(navigation.closest("fence", 2)).hasSize(2);
        }
    }

    // ------------------------------------------------------------------ saving

    @Nested
    @DisplayName("saving")
    class Saving {

        @Test
        @DisplayName("says which files could not be written, instead of claiming it saved")
        void reportsUnwritten() throws Exception {
            assertThat(registry.saveAllChecked()).isEmpty();

            java.nio.file.Files.writeString(directory.resolve("claims.yml"), "this: [is: not yaml");
            registry.stores().getFirst().load();
            registry.set("fences-enabled", "false");

            assertThat(registry.saveAllChecked()).containsExactly(directory.resolve("claims.yml"));
            assertThat(java.nio.file.Files.readString(directory.resolve("claims.yml")))
                    .as("the owner's broken file is left for them to fix").isEqualTo("this: [is: not yaml");
        }
    }
}
