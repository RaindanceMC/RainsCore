package de.raindancer.core.ui.preference;

import de.raindancer.core.ui.dialog.DialogAnswers;
import de.raindancer.core.ui.dialog.DialogPage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PreferencesTest {

    private final List<DialogPage> shown = new ArrayList<>();
    private final Player ada = mock(Player.class);
    private final Map<String, Boolean> stored = new HashMap<>();

    @BeforeEach
    void standIn() {
        when(ada.isOnline()).thenReturn(true);
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
    }

    @AfterEach
    void forget() {
        DialogPage.runtime(null);
        Preferences.clear();
    }

    private Preference toggle(String id, String group, String title, boolean defaultOn) {
        return Preference.toggle(id, group, title, "What " + title + " does.",
                player -> stored.getOrDefault(id, defaultOn), (player, on) -> stored.put(id, on));
    }

    private static String plain(net.kyori.adventure.text.Component text) {
        return PlainTextComponentSerializer.plainText().serialize(text);
    }

    @Test
    @DisplayName("a toggle says On or Off and flips when pressed")
    void toggleFlips() {
        Preference bars = toggle("healthbars:shown", "Health bars", "Health bars over mobs", true);
        assertThat(bars.state(ada)).isEqualTo("On");
        bars.next(ada);
        assertThat(bars.state(ada)).isEqualTo("Off");
        bars.next(ada);
        assertThat(bars.state(ada)).isEqualTo("On");
    }

    @Test
    @DisplayName("a choice goes round its options in order")
    void choiceCycles() {
        AtomicInteger at = new AtomicInteger();
        Preference style = Preference.choice("healthbars:style", "Health bars", "How health is drawn", "",
                List.of("Hearts", "Bar"), player -> at.get(), (player, index) -> at.set(index));
        assertThat(style.state(ada)).isEqualTo("Hearts");
        style.next(ada);
        assertThat(style.state(ada)).isEqualTo("Bar");
        style.next(ada);
        assertThat(style.state(ada)).isEqualTo("Hearts");
    }

    @Test
    @DisplayName("offered once per id, in order of their group and then title, and only those meant for the player")
    void registry() {
        Preferences.offer(null, toggle("pets:others", "Pets", "See other people's pets", true));
        Preferences.offer(null, toggle("chat:ping", "Chat", "Mention sound", true));
        Preferences.offer(null, toggle("chat:ping", "Chat", "A second with the same id", true));
        Preference staff = toggle("performance:alerts", "Performance", "Lag alerts", true)
                .shownTo(player -> player.hasPermission("rainsperformance.alerts"));
        Preferences.offer(null, staff);

        assertThat(Preferences.forPlayer(ada)).extracting(Preference::id).containsExactly("chat:ping", "pets:others");
        when(ada.hasPermission("rainsperformance.alerts")).thenReturn(true);
        assertThat(Preferences.forPlayer(ada)).extracting(Preference::id)
                .containsExactly("chat:ping", "performance:alerts", "pets:others");
        Preferences.withdraw(staff);
        assertThat(Preferences.forPlayer(ada)).extracting(Preference::id).containsExactly("chat:ping", "pets:others");
    }

    @Test
    @DisplayName("the window has one button per preference, saying its state; pressing flips it and shows the window again")
    void dialog() {
        Preferences.offer(null, toggle("pets:others", "Pets", "See other people's pets", true));
        AtomicInteger opened = new AtomicInteger();
        Preferences.offer(null, Preference.page("core:sidebar", "Sidebar", "Your sidebar", "What is on the right.",
                (player, parent) -> opened.incrementAndGet()));

        DialogPage page = PreferencesDialog.page(ada);
        assertThat(plain(page.title())).contains("Preferences");
        List<String> labels = page.pressable().stream().map(button -> plain(button.label())).toList();
        assertThat(labels).containsExactly("See other people's pets: On", "Your sidebar ›");

        page.button("See other people's pets").press(ada, DialogAnswers.none());
        assertThat(stored).containsEntry("pets:others", false);
        assertThat(shown).last().satisfies(again -> assertThat(again.pressable().stream()
                .map(button -> plain(button.label())).toList()).contains("See other people's pets: Off"));

        page.button("Your sidebar").press(ada, DialogAnswers.none());
        assertThat(opened).hasValue(1);
    }

    @Test
    @DisplayName("with nothing offered the window says so instead of standing empty")
    void nothingOffered() {
        DialogPage page = PreferencesDialog.page(ada);
        assertThat(page.plainText()).contains("Nothing to set");
    }
}
