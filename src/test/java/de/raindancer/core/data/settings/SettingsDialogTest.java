package de.raindancer.core.data.settings;

import de.raindancer.core.ui.dialog.DialogAnswers;
import de.raindancer.core.ui.dialog.DialogPage;
import io.papermc.paper.dialog.DialogResponseView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The settings as the game's own window: every page a dialog, every value changed in one. */
class SettingsDialogTest {

    @Settings(id = "claims", topics = {
            @Topic(path = "config/limits", title = "Limits", icon = Material.BARRIER,
                    description = "How much one player may have."),
            @Topic(path = "management/fences", title = "Fences", icon = Material.OAK_FENCE),
    })
    record ClaimConfig(
            @In("config/limits") @Title("Blocks per player") @Range(min = 0, max = 100_000)
            @Describe("How many blocks one player may claim.")
            int blocksPerPlayer,
            @In("management/fences") @Title("Show fences") boolean fencesEnabled,
            @In("management/fences") @Title("Fence style") Style fenceStyle,
            @In("config/limits") @Title("Welcome text") String welcome) {

        static final ClaimConfig DEFAULTS = new ClaimConfig(40_000, true, Style.SOLID, "Hello");
    }

    enum Style { SOLID, DASHED, NONE }

    @TempDir
    Path directory;
    private SettingsRegistry registry;
    private SettingsNavigation navigation;
    private final Player viewer = mock(Player.class);
    private final List<DialogPage> shown = new ArrayList<>();
    private final AtomicInteger exited = new AtomicInteger();
    private SettingsDialog dialog;

    @BeforeEach
    void setUp() {
        SettingsStore<ClaimConfig> store = new SettingsStore<>(SettingsSchema.of(ClaimConfig.class, ClaimConfig.DEFAULTS),
                directory.resolve("claims.yml"));
        store.load();
        registry = new SettingsRegistry();
        registry.add(store);
        navigation = new SettingsNavigation(registry);
        when(viewer.isOnline()).thenReturn(true);
        DialogPage.runtime(new DialogPage.Runtime() {
            @Override
            public void show(Player player, DialogPage page) {
                shown.add(page);
            }

            @Override
            public void onPlayerThread(Player player, Runnable task) {
                task.run();
            }
        });
        dialog = new SettingsDialog(viewer, navigation, null, exited::incrementAndGet);
    }

    @AfterEach
    void game() {
        DialogPage.runtime(null);
    }

    private static String plain(Component text) {
        return PlainTextComponentSerializer.plainText().serialize(text);
    }

    private static List<String> labels(DialogPage page) {
        return page.pressable().stream().map(button -> plain(button.label())).toList();
    }

    private static DialogAnswers typed(String value) {
        DialogResponseView view = mock(DialogResponseView.class);
        when(view.getText(SettingsDialog.VALUE)).thenReturn(value);
        return DialogAnswers.of(view);
    }

    @Test
    @DisplayName("the front page has a door per plugin, the changed settings and search, each saying what a click does")
    void frontPage() {
        DialogPage front = dialog.page(null);

        assertThat(plain(front.title())).contains("Settings");
        assertThat(labels(front)).contains("Claims ›", "Changed settings", "Search");
        assertThat(front.pressable()).allSatisfy(button -> assertThat(plain(button.tooltip())).contains("Click"));
    }

    @Test
    @DisplayName("a plugin's page opens its sections, and a section shows each setting with its value")
    void drillingIn() {
        dialog.page(null).button("Claims").press(viewer, DialogAnswers.none());
        DialogPage plugin = shown.getLast();
        assertThat(labels(plugin)).contains("Management ›");

        plugin.button("Management").press(viewer, DialogAnswers.none());
        assertThat(labels(shown.getLast())).contains("Fences ›");
        shown.getLast().button("Fences").press(viewer, DialogAnswers.none());
        DialogPage fences = shown.getLast();
        assertThat(labels(fences)).contains("Show fences » on", "Fence style » solid");
        assertThat(plain(fences.button("Show fences").tooltip())).contains("Click to switch off");
    }

    @Test
    @DisplayName("a switch flips on the spot and the page comes back saying so")
    void switchFlips() {
        DialogPage fences = dialog.page(pathOf("fences-enabled"));
        fences.button("Show fences").press(viewer, DialogAnswers.none());

        assertThat(registry.display("fences-enabled")).isEqualTo("off");
        assertThat(labels(shown.getLast())).contains("Show fences » off");
    }

    @Test
    @DisplayName("a number is typed into the window and saved; a wrong one is refused there, the value kept")
    void numberTyped() {
        DialogPage limits = dialog.page(pathOf("blocks-per-player"));
        limits.button("Blocks per player").press(viewer, DialogAnswers.none());
        DialogPage edit = shown.getLast();
        assertThat(edit.fields()).contains(SettingsDialog.VALUE);
        assertThat(edit.plainText()).contains("How many blocks one player may claim.").contains("From 0 to 100000");

        edit.button("Save").press(viewer, typed("250000"));
        assertThat(registry.display("blocks-per-player")).isEqualTo("40000");
        assertThat(shown.getLast().notice()).isNotBlank();

        edit.button("Save").press(viewer, typed("5000"));
        assertThat(registry.display("blocks-per-player")).isEqualTo("5000");
        assertThat(labels(shown.getLast())).contains("Blocks per player » 5000");
    }

    @Test
    @DisplayName("one of a named set is a button per answer, and a changed value can be put back")
    void optionsAndDefault() {
        dialog.page(pathOf("fences-enabled")).button("Fence style").press(viewer, DialogAnswers.none());
        DialogPage options = shown.getLast();
        assertThat(labels(options)).contains("solid", "dashed", "none");

        options.button("dashed").press(viewer, DialogAnswers.none());
        assertThat(registry.display("fence-style")).isEqualTo("dashed");

        dialog.page(pathOf("fences-enabled")).button("Fence style").press(viewer, DialogAnswers.none());
        shown.getLast().button("Put back the default").press(viewer, DialogAnswers.none());
        assertThat(registry.display("fence-style")).isEqualTo("solid");
    }

    @Test
    @DisplayName("search finds a setting by a word and opens it like its page does")
    void search() {
        dialog.page(null).button("Search").press(viewer, DialogAnswers.none());
        DialogPage asking = shown.getLast();
        assertThat(asking.fields()).contains(SettingsDialog.VALUE);

        asking.button("Search").press(viewer, typed("fence"));
        assertThat(labels(shown.getLast())).contains("Show fences » on", "Fence style » solid");
    }

    @Test
    @DisplayName("Back on the front page leaves the settings the way they were opened from")
    void backLeaves() {
        dialog.page(null).exitButton().press(viewer, DialogAnswers.none());
        assertThat(exited).hasValue(1);
    }

    private String pathOf(String key) {
        return navigation.pathOf(registry.setting(key).orElseThrow());
    }
}
