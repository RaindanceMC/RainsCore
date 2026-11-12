package de.raindancer.core.ui.dialog;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.text.Text;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * One window in the game's own dialog screen, the way /help draws its guide: a gradient heading and where the player
 * is ({@code Moderation › Steve › Punish}), some text, perhaps an item beside it, fields to fill in, buttons, and
 * Back or Close.
 *
 * <p>A button calls back into the server instead of running a command, so moving through dialogs fills nobody's chat
 * history, and its handler runs on the player's own thread with whatever was typed ({@link DialogAnswers}). The next
 * window is built there, fresh: a page shows things as they are when it opens.
 *
 * <p>A button the player may not use is drawn anyway, greyed, with the reason as its tooltip; pressing it brings the
 * same window back with the reason at the top. A dialog cannot disable a button, and a hidden one would make the
 * window a different shape for everybody, with no answer to "why is it not there".
 *
 * <p>Built with chained calls, then {@link #show(Player)}. Text is MiniMessage written by the caller — anything a
 * player typed goes through {@link Text#literal} first; the steps of the title are taken as written.
 */
public final class DialogPage {

    /** How wide text is drawn, in pixels. A dialog scrolls, so the length is free. */
    public static final int TEXT_WIDTH = 320;
    /** A button alone on its row. */
    public static final int WIDE = 300;
    /** A button with another beside it. */
    public static final int NARROW = 150;

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Pattern KEY = Pattern.compile("[a-z0-9_]+");
    private static final ClickCallback.Options CALLBACK = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(30)).build();
    /** How often a page was put in front of each player — so a confirmation can tell its Yes opened something. */
    private static final Map<Player, Integer> SHOWN = Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile Runtime runtime;

    private final Component title;
    private final List<Part> body = new ArrayList<>();
    private final List<Input> inputs = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();
    private final List<Button> buttons = new ArrayList<>();
    private Button exit;
    private boolean confirmation;
    private int columns = 1;
    private String notice;

    private DialogPage(Component title) {
        this.title = title;
    }

    /**
     * @param heading MiniMessage, usually a gradient: what this family of windows is, e.g. {@code Moderation}
     * @param trail   where in it the player is, outermost first — text, not markup
     */
    public static DialogPage titled(String heading, String... trail) {
        StringBuilder title = new StringBuilder(heading == null ? "" : heading);
        for (String step : trail) {
            if (step != null && !step.isBlank()) {
                title.append(" <dark_gray>›</dark_gray> <white>").append(Text.literal(step)).append("</white>");
            }
        }
        return new DialogPage(MINI.deserialize(title.toString()));
    }

    // ------------------------------------------------------------------ what is in it

    /** A paragraph of MiniMessage. */
    public DialogPage text(String miniMessage) {
        return text(MINI.deserialize(miniMessage == null ? "" : miniMessage));
    }

    public DialogPage text(Component text) {
        body.add(new Part(text, null, null));
        return this;
    }

    /**
     * Somebody's face, big, above a paragraph — when {@link Faces} has it and the viewer is on Java; the head item
     * beside the paragraph otherwise. Fetch it with {@link Faces#load} before building the page.
     */
    public DialogPage face(java.util.UUID who, Supplier<ItemStack> head, String miniMessage) {
        body.add(new Part(MINI.deserialize(miniMessage == null ? "" : miniMessage), head, who));
        return this;
    }

    /** An item with a paragraph beside it — a head beside who somebody is, a book beside what it is about. */
    public DialogPage item(Supplier<ItemStack> item, String miniMessage) {
        body.add(new Part(MINI.deserialize(miniMessage == null ? "" : miniMessage), item, null));
        return this;
    }

    /** A line of text to type, starting with {@code initial}. */
    public DialogPage field(String key, String label, String initial, int longest) {
        return input(new Input(key(key), MINI.deserialize(label), initial == null ? "" : initial,
                Math.max(1, longest), 0, null, false));
    }

    /** Several lines to type: a description, a note. */
    public DialogPage area(String key, String label, String initial, int longest, int lines) {
        return input(new Input(key(key), MINI.deserialize(label), initial == null ? "" : initial,
                Math.max(1, longest), Math.max(2, lines), null, false));
    }

    /** One of several options, as a dropdown on Java and a list on Bedrock. The first marked picked is picked. */
    public DialogPage choice(String key, String label, List<Choice> options) {
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("a choice needs options: " + key);
        }
        return input(new Input(key(key), MINI.deserialize(label), "", 0, 0, List.copyOf(options), false));
    }

    /** A switch. */
    public DialogPage toggle(String key, String label, boolean on) {
        return input(new Input(key(key), MINI.deserialize(label), "", 0, 0, null, on));
    }

    /** A button that does something. */
    public DialogPage button(String label, String tooltip, Consumer<DialogAnswers> onPress) {
        return button(true, label, tooltip, null, onPress);
    }

    /**
     * A button only some may use: for the others it is greyed, says {@code refusal} on hover, and brings this window
     * back with {@code refusal} at the top when pressed. The handler is not run for them.
     */
    public DialogPage button(boolean allowed, String label, String tooltip, String refusal, Consumer<DialogAnswers> onPress) {
        buttons.add(allowed ? callback(label, tooltip, 0, onPress) : locked(label, tooltip, refusal));
        return this;
    }

    /** The same at a width of its own, for a page that mixes wide and narrow buttons. */
    public DialogPage button(int width, String label, String tooltip, Consumer<DialogAnswers> onPress) {
        buttons.add(callback(label, tooltip, width, onPress));
        return this;
    }

    /** A button that opens a web page, after the client asks. */
    public DialogPage link(String label, String tooltip, String url) {
        buttons.add(new Button(MINI.deserialize(label), tooltip(tooltip), 0, true,
                (player, answers) -> { }, DialogAction.staticAction(ClickEvent.openUrl(url))));
        return this;
    }

    /** How many buttons side by side; one by default. */
    public DialogPage columns(int columns) {
        this.columns = Math.max(1, columns);
        return this;
    }

    /** Back instead of Close: the way out goes where the player came from. */
    public DialogPage back(Runnable where) {
        return exit("<gray>Back", where);
    }

    /** A way out of one's own. A null {@code where} only closes the window. */
    public DialogPage exit(String label, Runnable where) {
        this.exit = callback(label, null, NARROW, answers -> {
            if (where != null) {
                where.run();
            }
        });
        return this;
    }

    /**
     * Turns this into a question with two answers. Yes runs {@code onYes} and then goes {@code back} — unless what it
     * did already put another window in front of the player. No only goes {@code back}.
     */
    public DialogPage confirm(String yes, Runnable onYes, String no, Runnable back) {
        return confirm(yes, onYes, no, back, back);
    }

    /** The same, with Yes going on to {@code afterYes} rather than back — to the page that shows what it did. */
    public DialogPage confirm(String yes, Runnable onYes, String no, Runnable back, Runnable afterYes) {
        Runnable goBack = back == null ? () -> { } : back;
        Runnable goOn = afterYes == null ? () -> { } : afterYes;
        buttons.clear();
        buttons.add(callback("<green>" + yes, "<gray>Go ahead.", NARROW, answers -> {
            int before = shownTo(answersPlayer.get());
            onYes.run();
            Player player = answersPlayer.get();
            if (shownTo(player) == before && !looksAtAPage(player)) {
                goOn.run();
            }
        }));
        buttons.add(callback("<red>" + no, "<gray>Nothing happens.", NARROW, answers -> goBack.run()));
        confirmation = true;
        columns = 2;
        exit = null;
        return this;
    }

    /** Who pressed, while their handler runs — only a confirmation needs it, to count what it opened. */
    private static final ThreadLocal<Player> answersPlayer = new ThreadLocal<>();

    // ------------------------------------------------------------------ showing it

    public void show(Player player) {
        SHOWN.merge(player, 1, Integer::sum);
        runtime().show(player, this);
    }

    /** The same window again with a line at the top saying why something did not happen. */
    public DialogPage withNotice(String notice) {
        DialogPage copy = new DialogPage(title);
        copy.body.addAll(body);
        copy.inputs.addAll(inputs);
        copy.keys.addAll(keys);
        copy.buttons.addAll(buttons);
        copy.exit = exit;
        copy.confirmation = confirmation;
        copy.columns = columns;
        copy.notice = notice;
        return copy;
    }

    /** As the client gets it. Only on a server: the dialog registry is not there in a test. */
    public Dialog toDialog(Player player) {
        List<DialogBody> parts = new ArrayList<>();
        if (notice != null && !notice.isBlank()) {
            parts.add(DialogBody.plainMessage(MINI.deserialize("<red>" + Text.literal(notice)), TEXT_WIDTH));
        }
        for (Part part : body) {
            java.util.Optional<Component> face = part.face() == null || Faces.isBedrock(player.getUniqueId())
                    ? java.util.Optional.empty() : Faces.cached(part.face());
            if (face.isPresent()) {
                parts.add(DialogBody.plainMessage(face.get(), Faces.WIDTH));
                parts.add(DialogBody.plainMessage(part.text(), TEXT_WIDTH));
            } else if (part.item() == null) {
                parts.add(DialogBody.plainMessage(part.text(), TEXT_WIDTH));
            } else {
                parts.add(DialogBody.item(part.item().get()).showTooltip(false)
                        .description(DialogBody.plainMessage(part.text(), TEXT_WIDTH - 40)).build());
            }
        }
        List<DialogInput> fields = inputs.stream().map(Input::toInput).toList();
        DialogBase base = DialogBase.builder(title).canCloseWithEscape(true).pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE).body(parts).inputs(fields).build();
        DialogType type;
        if (confirmation) {
            type = DialogType.confirmation(buttons.get(0).toButton(player), buttons.get(1).toButton(player));
        } else {
            Button out = exitButton();
            type = DialogType.multiAction(pressable().stream().map(button -> button.toButton(player)).toList())
                    .exitAction(out == null ? null : out.toButton(player)).columns(columns).build();
        }
        return Dialog.create(factory -> factory.empty().base(base).type(type));
    }

    // ------------------------------------------------------------------ what a test can read

    public Component title() {
        return title;
    }

    /** Why the last press did nothing, at the top of the window; null when nothing was refused. */
    public String notice() {
        return notice;
    }

    public boolean isConfirmation() {
        return confirmation;
    }

    /** Everything in the text, plain, one paragraph a line — to check what a window says. */
    public String plainText() {
        StringBuilder all = new StringBuilder();
        for (Part part : body) {
            all.append(PlainTextComponentSerializer.plainText().serialize(part.text())).append('\n');
        }
        return all.toString();
    }

    /** The keys of the fields, in order. */
    public List<String> fields() {
        return inputs.stream().map(Input::key).toList();
    }

    /**
     * The buttons in the window, sized. A window with nothing to press offers its way out as the one button: the
     * client needs at least one.
     */
    public List<Button> pressable() {
        List<Button> sized = new ArrayList<>();
        for (Button button : buttons) {
            sized.add(button.sized(confirmation ? NARROW : columns == 1 ? WIDE : NARROW));
        }
        if (sized.isEmpty()) {
            sized.add(closeOrExit());
        }
        return sized;
    }

    /** The way out beside the buttons; null on a confirmation and when it is already the only button. */
    public Button exitButton() {
        return confirmation || buttons.isEmpty() ? null : closeOrExit();
    }

    /** The button whose label reads {@code label}, plain; for tests. */
    public Button button(String label) {
        List<Button> all = new ArrayList<>(pressable());
        if (exitButton() != null) {
            all.add(exitButton());
        }
        return all.stream().filter(button -> plain(button.label()).equals(label)
                        || plain(button.label()).startsWith(label)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no button " + label + " in "
                        + all.stream().map(button -> plain(button.label())).toList()));
    }

    // ------------------------------------------------------------------ pieces

    private Button closeOrExit() {
        return exit != null ? exit : callback("<gray>Close", null, NARROW, answers -> { });
    }

    private DialogPage input(Input input) {
        if (!keys.add(input.key())) {
            throw new IllegalArgumentException("two fields called " + input.key());
        }
        inputs.add(input);
        return this;
    }

    private static String key(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("a field's key is lower case letters, digits and _: " + key);
        }
        return key;
    }

    private Button callback(String label, String tooltip, int width, Consumer<DialogAnswers> onPress) {
        return new Button(MINI.deserialize(label), tooltip(tooltip), width, true, (player, answers) ->
                runtime().onPlayerThread(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    answersPlayer.set(player);
                    try {
                        onPress.accept(answers);
                    } finally {
                        answersPlayer.remove();
                    }
                }), null);
    }

    private Button locked(String label, String tooltip, String refusal) {
        String reason = refusal == null || refusal.isBlank() ? "Not for you" : refusal;
        Component shown = MINI.deserialize("<dark_gray>" + Text.literal(plain(MINI.deserialize(label))));
        Component hover = MINI.deserialize((tooltip == null ? "" : tooltip + "\n\n") + "<red>" + Text.literal(reason));
        return new Button(shown, hover, 0, false, (player, answers) ->
                runtime().onPlayerThread(player, () -> {
                    if (player.isOnline()) {
                        withNotice(reason).show(player);
                    }
                }), null);
    }

    private static Component tooltip(String tooltip) {
        return tooltip == null ? null : MINI.deserialize(tooltip);
    }

    private static int shownTo(Player player) {
        return player == null ? 0 : SHOWN.getOrDefault(player, 0);
    }

    private static boolean looksAtAPage(Player player) {
        InventoryView view = player == null ? null : player.getOpenInventory();
        return view != null && view.getTopInventory() != null
                && view.getTopInventory().getHolder(false) instanceof Menu;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** A label as a button holds it — about forty characters on one line; the whole belongs in the tooltip. */
    public static String shortened(String label, int longest) {
        String one = label == null ? "" : label.replaceAll("\\s+", " ").strip();
        return one.length() <= longest ? one : one.substring(0, longest - 1).strip() + "…";
    }

    // ------------------------------------------------------------------ the parts

    /** A paragraph, an item with one beside it, or somebody's face above one. */
    private record Part(Component text, Supplier<ItemStack> item, java.util.UUID face) {
    }

    /** One option of a {@link #choice}. */
    public record Choice(String id, String label, boolean picked) {

        public static Choice of(String id, String label) {
            return new Choice(id, label, false);
        }
    }

    private record Input(String key, Component label, String initial, int longest, int lines, List<Choice> options,
                         boolean on) {

        DialogInput toInput() {
            if (options != null) {
                List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
                boolean anyPicked = options.stream().anyMatch(Choice::picked);
                for (int at = 0; at < options.size(); at++) {
                    Choice choice = options.get(at);
                    entries.add(SingleOptionDialogInput.OptionEntry.create(choice.id(), MINI.deserialize(choice.label()),
                            anyPicked ? choice.picked() : at == 0));
                }
                return DialogInput.singleOption(key, label, entries).width(WIDE).labelVisible(true).build();
            }
            if (longest == 0) {
                return DialogInput.bool(key, label).initial(on).build();
            }
            return DialogInput.text(key, label).width(WIDE).labelVisible(true)
                    .initial(initial).maxLength(Math.max(longest, initial.length()))
                    .multiline(lines > 0 ? TextDialogInput.MultilineOptions.create(lines, Math.min(512, 9 * lines + 8))
                            : null)
                    .build();
        }
    }

    /** A button as the window will have it. */
    public static final class Button {

        private final Component label;
        private final Component tooltip;
        private final int width;
        private final boolean allowed;
        private final java.util.function.BiConsumer<Player, DialogAnswers> press;
        private final DialogAction fixed;

        private Button(Component label, Component tooltip, int width, boolean allowed,
                       java.util.function.BiConsumer<Player, DialogAnswers> press, DialogAction fixed) {
            this.label = label;
            this.tooltip = tooltip;
            this.width = width;
            this.allowed = allowed;
            this.press = press;
            this.fixed = fixed;
        }

        public Component label() {
            return label;
        }

        public Component tooltip() {
            return tooltip;
        }

        /** The tooltip as this player sees it: "Click to go" drawn as the mouse, as on a chest page's lore. */
        public Component tooltipFor(java.util.UUID player) {
            return tooltip == null ? null
                    : de.raindancer.core.ui.menu.ClickHints.rewrite(tooltip,
                    de.raindancer.core.ui.menu.ClickHints.audience(player));
        }

        public int width() {
            return width;
        }

        public boolean allowed() {
            return allowed;
        }

        /** What pressing it does — what the client's callback calls, and what a test calls. */
        public void press(Player player, DialogAnswers answers) {
            press.accept(player, answers == null ? DialogAnswers.none() : answers);
        }

        private Button sized(int auto) {
            return width > 0 ? this : new Button(label, tooltip, auto, allowed, press, fixed);
        }

        private ActionButton toButton(Player player) {
            DialogAction action = fixed != null ? fixed : DialogAction.customClick(
                    (response, audience) -> press(player, DialogAnswers.of(response)), CALLBACK);
            return ActionButton.builder(label).tooltip(tooltipFor(player.getUniqueId())).width(width > 0 ? width : NARROW).action(action).build();
        }
    }

    // ------------------------------------------------------------------ the client, or a test's stand-in

    /** Where pages go and where their buttons run. The game by default; a test puts its own in. */
    public interface Runtime {

        void show(Player player, DialogPage page);

        void onPlayerThread(Player player, Runnable task);
    }

    /** @param stand null puts the game back */
    public static void runtime(Runtime stand) {
        runtime = stand;
    }

    private static Runtime runtime() {
        Runtime set = runtime;
        return set != null ? set : Game.INSTANCE;
    }

    private enum Game implements Runtime {
        INSTANCE;

        @Override
        public void show(Player player, DialogPage page) {
            // A chest left open under a dialog stays open on the server and swallows the next click.
            InventoryView open = player.getOpenInventory();
            InventoryType type = open.getTopInventory().getType();
            if (type != InventoryType.CRAFTING && type != InventoryType.CREATIVE) {
                player.closeInventory();
            }
            player.showDialog(page.toDialog(player));
        }

        @Override
        public void onPlayerThread(Player player, Runnable task) {
            Plugin plugin = JavaPlugin.getProvidingPlugin(DialogPage.class);
            Scheduling.entity(plugin, player, task);
        }
    }
}
