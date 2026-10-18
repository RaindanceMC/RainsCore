package de.raindancer.core.data.settings;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading a settings record into a model both {@code config.yml} and the GUI can be built from.
 *
 * <h2>Why this is the first test written</h2>
 * The schema is the single source: the file on disk, its comments, the validation, the tab
 * completion and every settings screen are derived from it. A fault here is a fault in all five at
 * once, and four of those cannot be seen without a running server. So the contract is pinned down
 * here, in a test that needs nothing but a JVM.
 */
class SettingsSchemaTest {

    // ------------------------------------------------------------------ the fixtures

    /** A record exercising every supported component type and a two-level topic tree. */
    /**
     * The primitives a YAML parser does not produce.
     *
     * <p>A parser gives back {@code Double} and {@code Integer}; a record may declare {@code float},
     * {@code short} or {@code byte}. Reflection refuses to narrow, so a record with one {@code float}
     * in it was a plugin that would not start — and it failed while starting, with a stack trace
     * naming the constructor rather than the field.
     */
    @Settings(id = "narrow", topics = {
            @Topic(path = "config", title = "Config", icon = Material.PAPER,
                    description = "The awkward primitives."),
    })
    record NarrowConfig(
            @In("config") @Title("A float") float rate,
            @In("config") @Title("A short") short count,
            @In("config") @Title("A byte") byte level,
            @In("config") @Title("A double for comparison") double amount) {

        static final NarrowConfig DEFAULTS = new NarrowConfig(0.5f, (short) 3, (byte) 2, 1.5);
    }

    @Settings(id = "claims", topics = {
            @Topic(path = "management", title = "Management", icon = Material.IRON_AXE,
                    description = "What someone running a claim changes for other people."),
            @Topic(path = "management/fences", title = "Fences", icon = Material.OAK_FENCE,
                    description = "The fence a claim draws around itself."),
            @Topic(path = "config/limits", title = "Limits", icon = Material.BARRIER,
                    description = "How much one player may claim."),
    })
    record ClaimConfig(
            @In("management/fences") @Title("Show fences")
            @Describe("Draws a fence along the claim border.")
            @Icon(Material.OAK_FENCE)
            boolean fencesEnabled,

            @In("management/fences") @Title("Fence height") @Range(min = 1, max = 16)
            int fencesHeight,

            @In("management/fences") @Title("Fence tint")
            NamedTextColor fencesTint,

            @In("management/fences") @Title("Fence material")
            Material fencesMaterial,

            @In("config/limits") @Title("Blocks per player") @Range(min = 0, max = 1_000_000)
            long blocksPerPlayer,

            @In("config/limits") @Title("Tax rate") @Range(min = 0, max = 100)
            double taxPercent,

            @In("config/limits") @Title("Greeting")
            String greeting,

            @In("config/limits") @Title("Banned worlds")
            List<String> bannedWorlds,

            @In("config/limits") @Title("Default policy")
            Policy defaultPolicy,

            // A key that is not derived from the component name: what an existing server's
            // config.yml already calls this setting, so upgrading it needs no migration.
            @In("config/limits") @Title("Remove phantoms") @Key("gameplay.remove-phantoms")
            boolean removePhantoms
    ) {
        static final ClaimConfig DEFAULTS = new ClaimConfig(
                true, 3, NamedTextColor.AQUA, Material.OAK_FENCE,
                40_000L, 7.5, "Welcome", List.of("nether", "the_end"), Policy.ASK, true);
    }

    enum Policy { ALLOW, DENY, ASK }

    private static final SettingsSchema<ClaimConfig> SCHEMA =
            SettingsSchema.of(ClaimConfig.class, ClaimConfig.DEFAULTS);

    private static Setting<?> setting(String key) {
        return SCHEMA.setting(key).orElseThrow(() -> new AssertionError("no setting " + key));
    }

    // ------------------------------------------------------------------ identity

    @Test
    @DisplayName("the schema carries the plugin id from the annotation")
    void readsTheId() {
        assertThat(SCHEMA.id()).isEqualTo("claims");
        assertThat(SCHEMA.type()).isEqualTo(ClaimConfig.class);
    }

    // ------------------------------------------------------------------ keys

    @Nested
    @DisplayName("the YAML key")
    class Keys {

        @Test
        @DisplayName("is the component name in kebab-case when nothing says otherwise")
        void defaultsToKebabCase() {
            assertThat(SCHEMA.keys())
                    .contains("fences-enabled", "fences-height", "blocks-per-player", "tax-percent");
        }

        @Test
        @DisplayName("is @Key when one is given, so an existing config.yml keeps working")
        void honoursAnExplicitKey() {
            assertThat(SCHEMA.keys()).contains("gameplay.remove-phantoms");
            assertThat(SCHEMA.keys()).doesNotContain("remove-phantoms");
            assertThat(setting("gameplay.remove-phantoms").title()).isEqualTo("Remove phantoms");
        }

        /**
         * The order is load-bearing, and it was silently wrong once.
         *
         * <p>The schema kept its settings in {@code Map.copyOf(...)}, whose iteration order is
         * unspecified. {@link SettingsSchema#instantiate} pairs settings with the canonical
         * constructor's parameters by position, so a reordered map built every snapshot with the
         * components shuffled — which surfaced as {@code ClassCastException: Cannot cast Material
         * to String} from deep inside reflection, rather than as anything resembling the cause.
         */
        @Test
        @DisplayName("keys come back in the order the record declares them, always")
        void keysAreInDeclarationOrder() {
            assertThat(SCHEMA.keys()).containsExactly(
                    "fences-enabled", "fences-height", "fences-tint", "fences-material",
                    "blocks-per-player", "tax-percent", "greeting", "banned-worlds",
                    "default-policy", "gameplay.remove-phantoms");
            assertThat(SCHEMA.settings()).extracting(Setting::key)
                    .containsExactlyElementsOf(SCHEMA.keys());
        }

        @Test
        @DisplayName("is unique; two components cannot claim the same key")
        void refusesDuplicateKeys() {
            assertThatThrownBy(() -> SettingsSchema.of(DuplicateKeys.class, DuplicateKeys.DEFAULTS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("same-key");
        }

        @Test
        @DisplayName("asking for one that does not exist is empty, not an exception")
        void unknownKeyIsEmpty() {
            assertThat(SCHEMA.setting("nope.not.here")).isEmpty();
        }
    }

    // ------------------------------------------------------------------ types

    @Nested
    @DisplayName("the type of a setting")
    class Types {

        @Test
        @DisplayName("comes from the record component, not from a declared kind")
        void isTakenFromTheComponent() {
            assertThat(setting("fences-enabled").type()).isEqualTo(Boolean.class);
            assertThat(setting("fences-height").type()).isEqualTo(Integer.class);
            assertThat(setting("blocks-per-player").type()).isEqualTo(Long.class);
            assertThat(setting("tax-percent").type()).isEqualTo(Double.class);
            assertThat(setting("greeting").type()).isEqualTo(String.class);
            assertThat(setting("fences-tint").type()).isEqualTo(NamedTextColor.class);
            assertThat(setting("fences-material").type()).isEqualTo(Material.class);
            assertThat(setting("default-policy").type()).isEqualTo(Policy.class);
            assertThat(setting("banned-worlds").type()).isEqualTo(List.class);
        }

        @Test
        @DisplayName("a type nothing knows how to store is refused when the schema is read")
        void refusesUnsupportedTypes() {
            assertThatThrownBy(() -> SettingsSchema.of(Unsupported.class, Unsupported.DEFAULTS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Thread");
        }

        @Test
        @DisplayName("an enum offers its constants, so a GUI can cycle and a command can complete")
        void enumsOfferTheirChoices() {
            assertThat(setting("default-policy").choices())
                    .containsExactly("allow", "deny", "ask");
            assertThat(setting("fences-enabled").choices()).isEmpty();
        }

        @Test
        @DisplayName("a NamedTextColor offers the same sixteen names a GUI cycles and a command "
                + "completes, even though it is not a Java enum")
        void namedTextColorOffersItsChoices() {
            assertThat(setting("fences-tint").choices()).hasSize(16)
                    .contains("white", "black", "red", "aqua");
        }
    }

    // ------------------------------------------------------------------ defaults

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("come from the DEFAULTS instance, so the compiler checks them")
        void areReadFromTheInstance() {
            assertThat(setting("fences-enabled").defaultValue()).isEqualTo(true);
            assertThat(setting("fences-height").defaultValue()).isEqualTo(3);
            assertThat(setting("blocks-per-player").defaultValue()).isEqualTo(40_000L);
            assertThat(setting("tax-percent").defaultValue()).isEqualTo(7.5);
            assertThat(setting("greeting").defaultValue()).isEqualTo("Welcome");
            assertThat(setting("fences-tint").defaultValue()).isEqualTo(NamedTextColor.AQUA);
            assertThat(setting("default-policy").defaultValue()).isEqualTo(Policy.ASK);
            assertThat(setting("banned-worlds").defaultValue())
                    .isEqualTo(List.of("nether", "the_end"));
        }

        @Test
        @DisplayName("a default outside its own @Range is a mistake, and is caught at startup")
        void refusesADefaultOutsideItsRange() {
            assertThatThrownBy(() -> SettingsSchema.of(BadDefault.class, BadDefault.DEFAULTS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("height");
        }

        @Test
        @DisplayName("a null default is refused — every setting has to have a value")
        void refusesNullDefaults() {
            assertThatThrownBy(() -> SettingsSchema.of(NullDefault.class, NullDefault.DEFAULTS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("greeting");
        }
    }

    // ------------------------------------------------------------------ presentation

    @Nested
    @DisplayName("what a screen needs to draw a setting")
    class Presentation {

        @Test
        @DisplayName("title and description come from the annotations")
        void readsTitleAndDescription() {
            Setting<?> fences = setting("fences-enabled");
            assertThat(fences.title()).isEqualTo("Show fences");
            assertThat(fences.description()).isEqualTo("Draws a fence along the claim border.");
        }

        @Test
        @DisplayName("a missing @Title falls back to the component name, readably")
        void inventsATitleWhenNoneIsGiven() {
            assertThat(SettingsSchema.of(Bare.class, Bare.DEFAULTS).setting("fence-height")
                    .orElseThrow().title()).isEqualTo("Fence height");
        }

        @Test
        @DisplayName("a missing @Describe is empty rather than invented")
        void leavesDescriptionEmpty() {
            assertThat(setting("fences-height").description()).isEmpty();
        }

        @Test
        @DisplayName("an icon of its own is kept; without one the menu draws one for its kind, not its topic's")
        void inheritsTheTopicIcon() {
            assertThat(setting("fences-enabled").icon()).isEqualTo(Material.OAK_FENCE);
            // A page of settings all borrowing their topic's icon was a row of identical buttons.
            assertThat(setting("blocks-per-player").icon()).isEqualTo(Material.AIR);
        }

        @Test
        @DisplayName("bounds are carried so the GUI can clamp and the command can refuse")
        void carriesBounds() {
            assertThat(setting("fences-height").min()).isEqualTo(1);
            assertThat(setting("fences-height").max()).isEqualTo(16);
            assertThat(setting("greeting").min()).isNull();
        }
    }

    // ------------------------------------------------------------------ the topic tree

    @Nested
    @DisplayName("topics")
    class Topics {

        @Test
        @DisplayName("the roots are the ones this plugin actually uses, in declaration order")
        void rootsAreWhatThePluginBrought() {
            assertThat(SCHEMA.topics().roots())
                    .extracting(SettingsTopic::path)
                    .containsExactly("management", "config");
        }

        @Test
        @DisplayName("a declared root keeps its own title and icon")
        void declaredRootsKeepTheirOwnLook() {
            SettingsTopic management = SCHEMA.topics().at("management").orElseThrow();
            assertThat(management.title()).isEqualTo("Management");
            assertThat(management.icon()).isEqualTo(Material.IRON_AXE);
        }

        @Test
        @DisplayName("a subtopic hangs under its parent")
        void nestsSubtopics() {
            SettingsTopic management = SCHEMA.topics().at("management").orElseThrow();
            assertThat(management.children()).extracting(SettingsTopic::path)
                    .containsExactly("management/fences");
            assertThat(SCHEMA.topics().at("management/fences").orElseThrow().title())
                    .isEqualTo("Fences");
        }

        @Test
        @DisplayName("a subtopic whose parent was never declared gets one made for it")
        void createsMissingParents() {
            SettingsTopic config = SCHEMA.topics().at("config").orElseThrow();
            assertThat(config.children()).extracting(SettingsTopic::path)
                    .containsExactly("config/limits");
        }

        @Test
        @DisplayName("a well-known name gets a good title and icon without being declared")
        void knownNamesComeFurnished() {
            // "config" is one of the names RainsCore has an opinion about, so a plugin that only
            // declares "config/limits" still gets a sensible button rather than the word "Config".
            SettingsTopic config = SCHEMA.topics().at("config").orElseThrow();
            assertThat(config.title()).isEqualTo("Server settings");
            assertThat(config.icon()).isNotEqualTo(Material.AIR);
        }

        @Test
        @DisplayName("a name nobody knows gets a readable title from the path")
        void unknownNamesGetAReadableTitle() {
            SettingsSchema<OwnCategory> schema =
                    SettingsSchema.of(OwnCategory.class, OwnCategory.DEFAULTS);
            assertThat(schema.topics().at("ghast-lines").orElseThrow().title())
                    .isEqualTo("Ghast lines");
        }

        @Test
        @DisplayName("a topic holds the settings that named it, in declaration order")
        void collectsItsOwnSettings() {
            assertThat(SCHEMA.topics().at("management/fences").orElseThrow().settings())
                    .extracting(Setting::key)
                    .containsExactly("fences-enabled", "fences-height", "fences-tint",
                            "fences-material");
        }

        @Test
        @DisplayName("a topic with subtopics but no settings of its own is a menu, not a page")
        void distinguishesMenusFromPages() {
            SettingsTopic management = SCHEMA.topics().at("management").orElseThrow();
            assertThat(management.settings()).isEmpty();
            assertThat(management.isMenu()).isTrue();
            assertThat(SCHEMA.topics().at("management/fences").orElseThrow().isMenu()).isFalse();
        }

        @Test
        @DisplayName("a plugin brings whatever categories it likes, at the top level too")
        void aPluginBringsItsOwnCategories() {
            SettingsSchema<OwnCategory> schema =
                    SettingsSchema.of(OwnCategory.class, OwnCategory.DEFAULTS);
            assertThat(schema.topics().roots()).extracting(SettingsTopic::path)
                    .containsExactly("ghast-lines");
            assertThat(schema.topics().at("ghast-lines/flight").orElseThrow().title())
                    .isEqualTo("Flight");
        }

        @Test
        @DisplayName("topics nest as deep as a plugin wants, and every level in between exists")
        void nestsArbitrarilyDeep() {
            SettingsSchema<Deep> schema = SettingsSchema.of(Deep.class, Deep.DEFAULTS);
            assertThat(schema.topics().at("config/limits/claims/blocks")).isPresent();
            // Only the leaf was declared; the three levels above it were made on the way.
            assertThat(schema.topics().all()).extracting(SettingsTopic::path)
                    .contains("config", "config/limits", "config/limits/claims",
                            "config/limits/claims/blocks");
            assertThat(schema.topics().at("config/limits/claims").orElseThrow().parent().path())
                    .isEqualTo("config/limits");
            assertThat(schema.topics().at("config/limits/claims/blocks").orElseThrow().depth())
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a topic that holds nothing at all is not offered — no dead buttons")
        void hidesEmptyTopics() {
            SettingsSchema<WithEmpty> schema =
                    SettingsSchema.of(WithEmpty.class, WithEmpty.DEFAULTS);
            assertThat(schema.topics().at("config/unused").orElseThrow().isEmpty()).isTrue();
            assertThat(schema.topics().visibleRoots()).extracting(SettingsTopic::path)
                    .containsExactly("config");
            assertThat(schema.topics().at("config").orElseThrow().visibleChildren())
                    .extracting(SettingsTopic::path)
                    .containsExactly("config/used");
        }

        @Test
        @DisplayName("a setting must say which topic it is in")
        void refusesAHomelessSetting() {
            assertThatThrownBy(() -> SettingsSchema.of(Homeless.class, Homeless.DEFAULTS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("stray");
        }

        @Test
        @DisplayName("a setting pointing at a topic nobody declared is a typo, and is caught")
        void refusesAnUndeclaredTopic() {
            assertThatThrownBy(() -> SettingsSchema.of(Misfiled.class, Misfiled.DEFAULTS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("management/fnces");
        }
    }

    // ------------------------------------------------------------------ shape of the class

    @Nested
    @DisplayName("what may be a settings class")
    class Shape {

        @Test
        @DisplayName("it has to be a record — the immutability is the point")
        void refusesANonRecord() {
            assertThatThrownBy(() -> SettingsSchema.of(NotARecord.class, new NotARecord()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("record");
        }

        @Test
        @DisplayName("it has to carry @Settings")
        void refusesAnUnannotatedRecord() {
            assertThatThrownBy(() -> SettingsSchema.of(Unannotated.class, Unannotated.DEFAULTS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("@Settings");
        }

        @Test
        @DisplayName("a record with no components is refused rather than producing an empty menu")
        void refusesAnEmptyRecord() {
            assertThatThrownBy(() -> SettingsSchema.of(Empty.class, new Empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no settings");
        }
    }

    // ------------------------------------------------------------------ reading values back

    @Nested
    @DisplayName("reading a value out of an instance")
    class Reading {

        @Test
        @DisplayName("a setting can fetch its own value from any instance of the record")
        void readsFromAnInstance() {
            ClaimConfig other = new ClaimConfig(false, 9, NamedTextColor.RED, Material.STONE,
                    1L, 1.0, "Hi", List.of(), Policy.DENY, false);
            assertThat(setting("fences-enabled").valueIn(other)).isEqualTo(false);
            assertThat(setting("fences-height").valueIn(other)).isEqualTo(9);
            assertThat(setting("greeting").valueIn(other)).isEqualTo("Hi");
        }

        @Test
        @DisplayName("every setting reads back the default from the DEFAULTS instance")
        void defaultsRoundTrip() {
            for (Setting<?> each : SCHEMA.settings()) {
                assertThat(each.valueIn(ClaimConfig.DEFAULTS))
                        .as("%s", each.key())
                        .isEqualTo(each.defaultValue());
            }
        }
    }

    // ------------------------------------------------------------------ the bad fixtures

    @Settings(id = "dupes", topics = @Topic(path = "config/x", title = "X"))
    record DuplicateKeys(
            @In("config/x") @Key("same-key") boolean one,
            @In("config/x") @Key("same-key") boolean two) {
        static final DuplicateKeys DEFAULTS = new DuplicateKeys(true, true);
    }

    @Settings(id = "unsupported", topics = @Topic(path = "config/x", title = "X"))
    record Unsupported(@In("config/x") Thread thread) {
        static final Unsupported DEFAULTS = new Unsupported(Thread.currentThread());
    }

    @Settings(id = "bad-default", topics = @Topic(path = "config/x", title = "X"))
    record BadDefault(@In("config/x") @Range(min = 1, max = 4) int height) {
        static final BadDefault DEFAULTS = new BadDefault(99);
    }

    @Settings(id = "null-default", topics = @Topic(path = "config/x", title = "X"))
    record NullDefault(@In("config/x") String greeting) {
        static final NullDefault DEFAULTS = new NullDefault(null);
    }

    @Settings(id = "bare", topics = @Topic(path = "config/x", title = "X"))
    record Bare(@In("config/x") int fenceHeight) {
        static final Bare DEFAULTS = new Bare(1);
    }

    /** A plugin whose settings do not belong under any name RainsCore has heard of. */
    @Settings(id = "ghasts", topics = {
            @Topic(path = "ghast-lines/flight", title = "Flight", icon = Material.WHITE_HARNESS)})
    record OwnCategory(@In("ghast-lines/flight") @Title("Cruise speed") int cruiseSpeed) {
        static final OwnCategory DEFAULTS = new OwnCategory(4);
    }

    /** Four levels deep, with only the leaf declared. */
    @Settings(id = "deep", topics = {
            @Topic(path = "config/limits/claims/blocks", title = "Blocks")})
    record Deep(@In("config/limits/claims/blocks") int perPlayer) {
        static final Deep DEFAULTS = new Deep(1);
    }

    /** One topic with a setting in it and one with nothing, to prove the empty one is hidden. */
    @Settings(id = "with-empty", topics = {
            @Topic(path = "config/used", title = "Used"),
            @Topic(path = "config/unused", title = "Unused")})
    record WithEmpty(@In("config/used") boolean thing) {
        static final WithEmpty DEFAULTS = new WithEmpty(true);
    }

    @Settings(id = "homeless", topics = @Topic(path = "config/x", title = "X"))
    record Homeless(boolean stray) {
        static final Homeless DEFAULTS = new Homeless(true);
    }

    @Settings(id = "misfiled", topics = @Topic(path = "management/fences", title = "Fences"))
    record Misfiled(@In("management/fnces") boolean typo) {
        static final Misfiled DEFAULTS = new Misfiled(true);
    }

    @Settings(id = "empty", topics = @Topic(path = "config/x", title = "X"))
    record Empty() {
    }

    record Unannotated(boolean thing) {
        static final Unannotated DEFAULTS = new Unannotated(true);
    }

    @Settings(id = "not-a-record", topics = @Topic(path = "config/x", title = "X"))
    static final class NotARecord {
    }
    @Nested
    @DisplayName("the primitives a YAML parser does not produce")
    class NarrowPrimitives {

        @Test
        @DisplayName("a record with float, short and byte in it can be built at all")
        void buildsNarrowPrimitives() {
            SettingsSchema<NarrowConfig> schema =
                    SettingsSchema.of(NarrowConfig.class, NarrowConfig.DEFAULTS);

            NarrowConfig built = schema.instantiate(java.util.Map.of());

            assertThat(built)
                    .as("reflection will not hand a Double to a float parameter, so this used to "
                            + "throw while the plugin was starting")
                    .isEqualTo(NarrowConfig.DEFAULTS);
        }

        @Test
        @DisplayName("a value read as a Double reaches a float field as a float")
        void narrowsAReadValue() {
            SettingsSchema<NarrowConfig> schema =
                    SettingsSchema.of(NarrowConfig.class, NarrowConfig.DEFAULTS);

            NarrowConfig built = schema.instantiate(java.util.Map.of(
                    "rate", 0.25d, "count", 7, "level", 4));

            assertThat(built.rate()).isEqualTo(0.25f);
            assertThat(built.count()).isEqualTo((short) 7);
            assertThat(built.level()).isEqualTo((byte) 4);
        }
    }


    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a key that is a value and also holds another, or one two records share, is a clash")
    void clashes() {
        assertThat(SettingsSchema.clashes(java.util.List.of("perks", "perks.start-share", "announce"), java.util.List.of()))
                .singleElement().asString().contains("'perks'").contains("perks.start-share");
        assertThat(SettingsSchema.clashes(java.util.List.of("enabled", "per-day"), java.util.List.of("enabled", "choices")))
                .singleElement().asString().contains("'enabled'");
        assertThat(SettingsSchema.clashes(java.util.List.of("quests.enabled"), java.util.List.of("orders.enabled", "quests")))
                .singleElement().asString().contains("'quests'");
        assertThat(SettingsSchema.clashes(java.util.List.of("perks.enabled", "perks.start-share"), java.util.List.of()))
                .isEmpty();
    }
}
