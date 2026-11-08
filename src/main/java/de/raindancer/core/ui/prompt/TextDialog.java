package de.raindancer.core.ui.prompt;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.text.Text;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Type a value" as the game's own dialog: a text field, Use and Cancel — the window the help guide already asks
 * its questions in. What {@link AnvilInput} does with an anvil, done the way the client does it, so it looks like
 * Minecraft rather than like a workaround with a paper in a slot.
 *
 * <p>The parser's verdict comes on Use rather than per keystroke: an answer it refuses brings the same dialog back
 * with the reason under the field and the text still in it. Cancel, and the exit button, hand back to the caller;
 * Escape closes the dialog and hands back too, because a player who pressed it expects to be where they were.
 */
public final class TextDialog {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String ANSWER = "answer";
    private static final int WIDTH = 300;
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
            .uses(1).lifetime(Duration.ofMinutes(10)).build();

    private TextDialog() {
    }

    /**
     * @param title    what is being asked — text, not markup
     * @param start    what the field starts with; may be null
     * @param longest  how many characters the field takes; at least 1
     * @param onCancel closed without an answer; may be null
     */
    public static <T> void ask(Player player, String title, String start, int longest, Parsers.Parser<T> parser,
                               Consumer<T> onAnswer, Runnable onCancel) {
        ask(player, title, start, longest, parser, onAnswer, onCancel, null);
    }

    private static <T> void ask(Player player, String title, String start, int longest, Parsers.Parser<T> parser,
                                Consumer<T> onAnswer, Runnable onCancel, String problem) {
        Plugin plugin = JavaPlugin.getProvidingPlugin(TextDialog.class);
        Runnable cancel = onCancel == null ? () -> { } : onCancel;
        List<DialogBody> body = new ArrayList<>();
        if (problem != null && !problem.isBlank()) {
            body.add(DialogBody.plainMessage(MINI.deserialize("<red>" + Text.literal(problem)), WIDTH));
        }
        DialogInput field = DialogInput.text(ANSWER, Component.text(title == null ? "" : title))
                .initial(start == null ? "" : start)
                .maxLength(Math.max(1, Math.max(longest, start == null ? 0 : start.length())))
                .width(WIDTH).labelVisible(true).build();
        ActionButton use = ActionButton.builder(MINI.deserialize("<green>Use"))
                .tooltip(MINI.deserialize("<gray>Take what is typed")).width(120)
                .action(DialogAction.customClick((response, audience) -> {
                    String typed = response.getText(ANSWER);
                    Parsed<T> parsed;
                    try {
                        parsed = parser.parse(typed == null ? "" : typed);
                    } catch (RuntimeException broken) {
                        parsed = Parsed.no("That could not be read.");
                    }
                    Parsed<T> verdict = parsed;
                    Scheduling.entity(plugin, player, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (verdict.isOk()) {
                            onAnswer.accept(verdict.value());
                        } else {
                            ask(player, title, typed, longest, parser, onAnswer, onCancel, verdict.problem());
                        }
                    });
                }, ONCE)).build();
        ActionButton leave = ActionButton.builder(MINI.deserialize("<red>Cancel"))
                .tooltip(MINI.deserialize("<gray>Leave it as it is")).width(120)
                .action(DialogAction.customClick((response, audience) ->
                        Scheduling.entity(plugin, player, () -> {
                            if (player.isOnline()) {
                                cancel.run();
                            }
                        }), ONCE)).build();
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(title == null ? "" : title)).canCloseWithEscape(true).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE).body(body).inputs(List.of(field)).build())
                .type(DialogType.confirmation(use, leave)));
        player.showDialog(dialog);
    }
}
