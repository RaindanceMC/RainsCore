package de.raindancer.core.ui.travel;

import de.raindancer.core.ui.dialog.DialogAnswers;
import de.raindancer.core.ui.dialog.DialogPage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TravelDialogTest {

    private final List<DialogPage> shown = new ArrayList<>();
    private final Player ada = mock(Player.class);

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
        TravelDoors.clear();
    }

    private static String plain(net.kyori.adventure.text.Component text) {
        return PlainTextComponentSerializer.plainText().serialize(text);
    }

    @Test
    @DisplayName("every way to travel is a button, in the order the doors ask for, each once")
    void doorsInOrder() {
        AtomicInteger homes = new AtomicInteger();
        TravelDoors.offer(null, TravelDoor.of("warp:warps", 20, "Warps", "Places the server set up.",
                (player, parent) -> { }));
        TravelDoors.offer(null, TravelDoor.of("homes:homes", 10, "Homes", "Your own places.",
                (player, parent) -> homes.incrementAndGet()));
        TravelDoors.offer(null, TravelDoor.of("homes:homes", 10, "Homes again", "", (player, parent) -> { }));

        DialogPage page = TravelDialog.page(ada);
        assertThat(plain(page.title())).contains("Travel");
        assertThat(page.pressable()).extracting(button -> plain(button.label())).containsExactly("Homes", "Warps");
        page.button("Homes").press(ada, DialogAnswers.none());
        assertThat(homes).hasValue(1);
    }

    @Test
    @DisplayName("a way the player may not use now is greyed with its reason")
    void lockedSaysWhy() {
        AtomicInteger back = new AtomicInteger();
        TravelDoors.offer(null, TravelDoor.of("tpa:back", 50, "Back", "Where you were before.",
                (player, parent) -> back.incrementAndGet()).lockedWhen(player -> Optional.of("Nowhere to go back to")));

        DialogPage.Button button = TravelDialog.page(ada).button("Back");
        assertThat(button.allowed()).isFalse();
        button.press(ada, DialogAnswers.none());
        assertThat(back).hasValue(0);
        assertThat(shown).last().extracting(DialogPage::notice).isEqualTo("Nowhere to go back to");
    }

    @Test
    @DisplayName("with no way to travel installed the window says so")
    void none() {
        assertThat(TravelDialog.page(ada).plainText()).contains("No way to travel");
        assertThat(TravelDoors.any()).isFalse();
    }

    @Test
    @DisplayName("every way to travel says what a click does")
    void clickHints() {
        TravelDoors.offer(null, TravelDoor.of("warp:warps", 20, "Warps", "Places the server set up.",
                (player, parent) -> { }));
        assertThat(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(TravelDialog.page(ada).button("Warps").tooltip())).contains("Click to");
    }
}
