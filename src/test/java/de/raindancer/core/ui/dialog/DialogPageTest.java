package de.raindancer.core.ui.dialog;

import de.raindancer.core.ui.chat.Brand;
import io.papermc.paper.dialog.DialogResponseView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DialogPageTest {

    /** What was put on screen, in order, and the tasks that were sent to a player's thread. */
    private final List<DialogPage> shown = new ArrayList<>();
    private final List<Runnable> queued = new ArrayList<>();
    private final Player player = mock(Player.class);

    @BeforeEach
    void fakeTheClient() {
        when(player.isOnline()).thenReturn(true);
        DialogPage.runtime(new DialogPage.Runtime() {
            @Override
            public void show(Player viewer, DialogPage page) {
                shown.add(page);
            }

            @Override
            public void onPlayerThread(Player viewer, Runnable task) {
                queued.add(task);
            }
        });
    }

    @AfterEach
    void realClient() {
        DialogPage.runtime(null);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private void runQueued() {
        List<Runnable> now = new ArrayList<>(queued);
        queued.clear();
        now.forEach(Runnable::run);
    }

    private static DialogAnswers answers(String key, String value) {
        DialogResponseView view = mock(DialogResponseView.class);
        when(view.getText(key)).thenReturn(value);
        return DialogAnswers.of(view);
    }

    @Test
    @DisplayName("the title is the heading, then where one is, each step taken as written")
    void titleIsATrail() {
        DialogPage page = DialogPage.titled("<gradient:#F87171:#C084FC>Moderation</gradient>", "<red>Bob", "Punish");
        assertThat(plain(page.title())).isEqualTo("Moderation › <red>Bob › Punish");
    }

    @Test
    @DisplayName("a page with nothing to press offers its way out as the one button")
    void emptyPageStillHasAButton() {
        DialogPage page = DialogPage.titled("Help").text("Nothing here.");
        assertThat(page.pressable()).extracting(button -> plain(button.label())).containsExactly("Close");
        assertThat(page.exitButton()).isNull();
    }

    @Test
    @DisplayName("with buttons, the way out is the exit: Close by default, Back when there is somewhere to go")
    void exitIsBackOrClose() {
        DialogPage closing = DialogPage.titled("Help").button("Go", null, answers -> { });
        assertThat(plain(closing.exitButton().label())).isEqualTo("Close");

        AtomicInteger back = new AtomicInteger();
        DialogPage page = DialogPage.titled("Help").button("Go", null, answers -> { }).back(back::incrementAndGet);
        assertThat(plain(page.exitButton().label())).isEqualTo("Back");
        page.exitButton().press(player, DialogAnswers.none());
        runQueued();
        assertThat(back).hasValue(1);
    }

    @Test
    @DisplayName("one column has wide buttons, two narrow ones, unless a button says otherwise")
    void widthsFollowTheColumns() {
        DialogPage one = DialogPage.titled("Help").button("A", null, answers -> { });
        assertThat(one.pressable().getFirst().width()).isEqualTo(DialogPage.WIDE);
        DialogPage two = DialogPage.titled("Help").columns(2).button("A", null, answers -> { });
        assertThat(two.pressable().getFirst().width()).isEqualTo(DialogPage.NARROW);
    }

    @Test
    @DisplayName("a press runs on the player's own thread with what was typed, and not for somebody who left")
    void pressRunsOnThePlayersThread() {
        AtomicReference<String> typed = new AtomicReference<>();
        DialogPage page = DialogPage.titled("Notes").field("note", "A note", "", 200)
                .button("Save", null, answers -> typed.set(answers.text("note")));

        page.button("Save").press(player, answers("note", "  griefed the spawn  "));
        assertThat(typed.get()).isNull();
        runQueued();
        assertThat(typed.get()).isEqualTo("griefed the spawn");

        typed.set(null);
        when(player.isOnline()).thenReturn(false);
        page.button("Save").press(player, answers("note", "again"));
        runQueued();
        assertThat(typed.get()).isNull();
    }

    @Test
    @DisplayName("a button somebody may not use is shown, greyed with the reason, and pressing it says why")
    void lockedButtonsExplainThemselves() {
        AtomicInteger muted = new AtomicInteger();
        DialogPage page = DialogPage.titled("Punish").text("What to do.")
                .button(false, "<yellow>Mute", "<gray>Stops them talking.", "You may not mute",
                        answers -> muted.incrementAndGet());

        DialogPage.Button mute = page.button("Mute");
        assertThat(mute.allowed()).isFalse();
        assertThat(plain(mute.tooltip())).contains("You may not mute");

        mute.press(player, DialogAnswers.none());
        runQueued();
        assertThat(muted).hasValue(0);
        assertThat(shown).hasSize(1);
        assertThat(shown.getFirst().notice()).isEqualTo("You may not mute");
        assertThat(plain(shown.getFirst().title())).isEqualTo(plain(page.title()));
    }

    @Test
    @DisplayName("answers are never null: a missing field reads as empty, a missing switch as off")
    void answersAreNeverNull() {
        DialogAnswers none = DialogAnswers.none();
        assertThat(none.text("anything")).isEmpty();
        assertThat(none.flag("anything")).isFalse();
        assertThat(none.choice("anything")).isEmpty();
    }

    @Test
    @DisplayName("two inputs with one key, or a key the client would refuse, are a mistake caught at once")
    void inputKeysAreChecked() {
        assertThatThrownBy(() -> DialogPage.titled("x").field("a", "A", "", 5).field("a", "B", "", 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DialogPage.titled("x").field("Not ok!", "A", "", 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a long label is cut to what a button shows, the whole of it kept for the tooltip")
    void longLabelsAreShortened() {
        assertThat(DialogPage.shortened("a short one", 40)).isEqualTo("a short one");
        String cut = DialogPage.shortened("x".repeat(60), 40);
        assertThat(cut).hasSize(40).endsWith("…");
        assertThat(DialogPage.shortened("two\nlines", 40)).isEqualTo("two lines");
    }

    @Test
    @DisplayName("confirming: Yes does it and goes back, unless what it did put something else on screen; No only goes back")
    void confirmation() {
        AtomicInteger done = new AtomicInteger();
        AtomicInteger back = new AtomicInteger();
        DialogPage ask = DialogPage.titled("Moderation").text("Ban Bob?")
                .confirm("Yes, do it", done::incrementAndGet, "No", back::incrementAndGet);
        assertThat(ask.isConfirmation()).isTrue();
        assertThat(ask.pressable()).extracting(button -> plain(button.label())).containsExactly("Yes, do it", "No");

        ask.pressable().get(0).press(player, DialogAnswers.none());
        runQueued();
        assertThat(done).hasValue(1);
        assertThat(back).hasValue(1);

        ask.pressable().get(1).press(player, DialogAnswers.none());
        runQueued();
        assertThat(done).hasValue(1);
        assertThat(back).hasValue(2);

        AtomicInteger onward = new AtomicInteger();
        DialogPage.titled("Moderation").text("Mute Bob?")
                .confirm("Yes", done::incrementAndGet, "No", back::incrementAndGet, onward::incrementAndGet)
                .pressable().get(0).press(player, DialogAnswers.none());
        runQueued();
        assertThat(onward).hasValue(1);
        assertThat(back).hasValue(2);

        DialogPage next = DialogPage.titled("Moderation").text("Done.");
        DialogPage leads = DialogPage.titled("Moderation").text("Ban Bob?")
                .confirm("Yes", () -> next.show(player), "No", back::incrementAndGet);
        leads.pressable().get(0).press(player, DialogAnswers.none());
        runQueued();
        assertThat(back).hasValue(2);
        assertThat(shown).containsExactly(next);
    }

    @Test
    @DisplayName("a chest page opened from a dialog goes Back to the dialog")
    void chestPagesReturnToTheDialog() {
        AtomicInteger reopened = new AtomicInteger();
        DialogReturn parent = new DialogReturn(player, new Brand("Test"), "Punish", reopened::incrementAndGet);
        assertThat(parent.breadcrumb()).isEqualTo("Punish");
        parent.open();
        assertThat(reopened).hasValue(1);
    }
}
