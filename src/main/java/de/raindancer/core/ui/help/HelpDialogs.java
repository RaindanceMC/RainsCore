package de.raindancer.core.ui.help;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.command.CommandNote;
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
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The guide's conversation, as Minecraft's own dialog windows: a greeting with the subjects on offer and a box to
 * type a question into, then a subject's opening words with the questions one may ask, then an answer with the
 * next question one click away.
 *
 * <p>Every window is built when it is opened, from the library and the settings as they are at that moment — an
 * owner who changes the tax sees the guide say the new number on the next click, with nothing to reload.
 *
 * <p>Buttons call back into the server ({@link DialogAction#customClick(io.papermc.paper.registry.data.dialog.action.DialogActionCallback, ClickCallback.Options)})
 * rather than running {@code /help …} commands, so moving through the guide never fills anybody's chat history.
 * The callbacks hop back onto the player's own thread before they open the next window.
 */
public final class HelpDialogs {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final ClickCallback.Options CALLBACK = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(30)).build();

    /** How wide the guide's words are drawn, in pixels. A dialog scrolls, so the length is free. */
    private static final int TEXT_WIDTH = 320;
    private static final int WIDE_BUTTON = 300;
    private static final int BUTTON = 150;
    private static final String ASK = "question";

    private final HelpDesk desk;
    private final Player reader;
    private final HelpContext context;
    private final HelpLibrary library;

    private HelpDialogs(HelpDesk desk, Player reader) {
        this.desk = desk;
        this.reader = reader;
        this.context = desk.contextFor(reader);
        this.library = desk.library();
    }

    /** For this reader, as things stand right now. */
    public static HelpDialogs forReader(HelpDesk desk, Player reader) {
        return new HelpDialogs(desk, reader);
    }

    // ------------------------------------------------------------------ the windows

    /** The greeting: who the guide is, the subjects, and a box for a question. */
    public void home(boolean greet) {
        if (greet) {
            voice();
        }
        Map<String, List<HelpTopic>> shelves = library.byCategory(context);
        int count = shelves.values().stream().mapToInt(List::size).sum();

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.item(new ItemStack(Material.ENCHANTED_BOOK))
                .description(DialogBody.plainMessage(line("help.greeting", true), TEXT_WIDTH - 40))
                .showTooltip(false).build());
        body.add(DialogBody.plainMessage(line("help.greeting-hint", false, "topics", String.valueOf(count)),
                TEXT_WIDTH));

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(line("help.ask-button", false), plain("help.ask-tooltip"), BUTTON,
                (response, player) -> {
                    String asked = response.getText(ASK);
                    then(dialogs -> dialogs.search(asked == null ? "" : asked));
                }));
        if (exists("commands")) {
            buttons.add(ActionButton.builder(line("help.commands-button", false)).tooltip(plain("help.commands-tooltip"))
                    .width(BUTTON).action(DialogAction.staticAction(ClickEvent.runCommand("/commands"))).build());
        }
        if (shelves.size() <= 1) {
            // One shelf is no choice: the topics themselves.
            shelves.values().forEach(topics -> topics.forEach(topic -> buttons.add(topicButton(topic, BUTTON))));
        } else {
            shelves.forEach((category, topics) -> buttons.add(button(
                    MINI.deserialize("<white>" + Text.literal(category)),
                    MINI.deserialize("<gray>" + Text.literal(String.join(", ",
                            topics.stream().map(HelpTopic::title).toList()))),
                    BUTTON, (response, player) -> then(dialogs -> dialogs.category(category)))));
        }
        String wiki = desk.settings().guideWiki();
        if (wiki != null && wiki.startsWith("https://")) {
            buttons.add(ActionButton.builder(line("help.wiki-button", false)).tooltip(Component.text(wiki))
                    .width(BUTTON).action(DialogAction.staticAction(ClickEvent.openUrl(wiki))).build());
        }
        show(null, body, List.of(DialogInput.text(ASK, line("help.ask-label", false)).width(WIDE_BUTTON)
                        .maxLength(100).labelVisible(true).build()),
                buttons, ActionButton.builder(line("help.goodbye", false)).width(BUTTON).build(), 2);
    }

    /** One shelf: its topics. */
    public void category(String category) {
        List<HelpTopic> topics = library.byCategory(context).getOrDefault(category, List.of());
        if (topics.isEmpty()) {
            home(false);
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        topics.forEach(topic -> buttons.add(topicButton(topic, BUTTON)));
        show(category, List.of(DialogBody.item(new ItemStack(HelpCategories.icon(category))).showTooltip(false)
                        .description(DialogBody.plainMessage(line("help.category-intro", false, "category", category),
                                TEXT_WIDTH - 40)).build()),
                List.of(), buttons, back(dialogs -> dialogs.home(false)), 2);
    }

    /** A subject: the guide's opening words, and every question this reader may ask about it. */
    public void topic(HelpTopic topic) {
        List<DialogBody> body = new ArrayList<>();
        Component opening = render(topic.opening());
        body.add(DialogBody.item(icon(topic)).showTooltip(false)
                .description(DialogBody.plainMessage(opening, TEXT_WIDTH - 40))
                .build());
        if (topic.generated()) {
            body.add(DialogBody.plainMessage(line("help.generated-note", false), TEXT_WIDTH));
        }

        List<ActionButton> buttons = new ArrayList<>();
        for (HelpSection section : HelpLibrary.sectionsFor(topic, context)) {
            buttons.add(button(MINI.deserialize("<white>" + Text.literal(section.question())), null, WIDE_BUTTON,
                    (response, player) -> then(dialogs -> dialogs.answer(topic, section))));
        }
        if (!notesFor(topic).isEmpty()) {
            buttons.add(button(line("help.which-commands", false), null, WIDE_BUTTON,
                    (response, player) -> then(dialogs -> dialogs.commands(topic))));
        }
        for (String id : topic.related()) {
            library.byId(id).filter(related -> HelpLibrary.visible(related, context)).ifPresent(related ->
                    buttons.add(button(line("help.related", false, "title", related.title()),
                            Component.text(related.summary()), WIDE_BUTTON,
                            (response, player) -> then(dialogs -> dialogs.topic(related)))));
        }
        String wiki = topic.wiki();
        if (wiki != null) {
            buttons.add(ActionButton.builder(line("help.more-on-wiki", false)).tooltip(Component.text(wiki))
                    .width(WIDE_BUTTON).action(DialogAction.staticAction(ClickEvent.openUrl(wiki))).build());
        }
        String shelf = topic.category();
        boolean shelves = library.byCategory(context).size() > 1;
        show(topic.title(), body, List.of(), buttons, back(dialogs -> {
            if (shelves) {
                dialogs.category(shelf);
            } else {
                dialogs.home(false);
            }
        }), 1);
    }

    /** One answer, with the next question one click away. */
    public void answer(HelpTopic topic, HelpSection section) {
        List<HelpSection> asked = HelpLibrary.sectionsFor(topic, context);
        int index = asked.indexOf(section);
        List<DialogBody> body = List.of(DialogBody.plainMessage(MINI.deserialize("<bold><#C4B5FD>"
                + Text.literal(section.question()) + "</#C4B5FD></bold>\n\n").append(render(section.text())), TEXT_WIDTH));

        List<ActionButton> buttons = new ArrayList<>();
        if (index >= 0 && index + 1 < asked.size()) {
            HelpSection next = asked.get(index + 1);
            buttons.add(button(line("help.next-question", false, "question", next.question()), null, WIDE_BUTTON,
                    (response, player) -> then(dialogs -> dialogs.answer(topic, next))));
        }
        // Every command the answer talks about, one click from being run — at most four, the answer is not a menu.
        List<CommandNote> mine = visibleNotes();
        int offered = 0;
        for (String usage : HelpText.commandsMentioned(section.text(), context)) {
            if (offered == 4) {
                break;
            }
            var match = CommandForms.forUsage(usage, mine);
            if (match.isPresent()) {
                buttons.add(formButton(match.get().note(), match.get().form(), dialogs -> dialogs.answer(topic, section)));
                offered++;
            }
        }
        buttons.add(button(line("help.something-else", false), null, WIDE_BUTTON,
                (response, player) -> then(dialogs -> dialogs.home(false))));
        show(topic.title(), body, List.of(), buttons, back(dialogs -> dialogs.topic(topic)), 1);
    }

    /** The commands of a subject this reader may use — each a button that opens it, ready to run. */
    public void commands(HelpTopic topic) {
        List<CommandNote> notes = notesFor(topic);
        List<ActionButton> buttons = new ArrayList<>();
        for (CommandNote note : notes) {
            buttons.add(commandButton(note, BUTTON, dialogs -> dialogs.commands(topic)));
        }
        String intro = notes.isEmpty() ? "help.commands-none" : "help.commands-intro";
        show(topic.title(), List.of(DialogBody.plainMessage(line(intro, false, "title", topic.title()), TEXT_WIDTH)), List.of(),
                buttons, back(dialogs -> dialogs.topic(topic)), 2);
    }

    /** One command: every way to use it, each a button — or straight to its form when there is only one. */
    public void command(CommandNote note, Consumer<HelpDialogs> backTo) {
        List<CommandForm> forms = CommandForms.of(note);
        if (forms.size() == 1) {
            form(note, forms.getFirst(), backTo, null);
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (CommandForm form : forms) {
            buttons.add(formButton(note, form, dialogs -> dialogs.command(note, backTo)));
        }
        show(note.slashed(), List.of(DialogBody.plainMessage(MINI.deserialize("<" + HelpText.LINK + ">"
                        + Text.literal(note.slashed()) + "</" + HelpText.LINK + "> <gray>— " + Text.literal(note.sentence())
                        + "\n\n").append(line("help.command-pick", false)), TEXT_WIDTH)),
                List.of(), buttons, back(backTo), 1);
    }

    /**
     * A command as a form: a box per argument (a list of who is online where it wants a player, a pick where it takes
     * fixed words), and Run. Running is the player typing it — the same permissions, the same command blocks, the
     * same messages — just without the typing.
     */
    public void form(CommandNote note, CommandForm form, Consumer<HelpDialogs> backTo, String complaint) {
        List<DialogBody> body = new ArrayList<>();
        StringBuilder text = new StringBuilder("<bold><" + HelpText.LINK + ">" + Text.literal(form.usage()) + "</"
                + HelpText.LINK + "></bold>\n<gray>" + Text.literal(note.sentence()));
        if (!form.note().isEmpty()) {
            text.append("\n<dark_gray>").append(Text.literal(form.note()));
        }
        body.add(DialogBody.plainMessage(MINI.deserialize(text.toString()), TEXT_WIDTH));
        if (complaint != null) {
            body.add(DialogBody.plainMessage(line("help.form-missing", false, "field", complaint), TEXT_WIDTH));
        }
        List<DialogInput> inputs = new ArrayList<>();
        for (CommandForm.Field field : form.fields()) {
            inputs.add(input(field));
        }
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(line("help.run", false), Component.text(form.usage()), BUTTON, (response, player) -> {
            java.util.Map<String, String> values = new java.util.HashMap<>();
            for (CommandForm.Field field : form.fields()) {
                String value = response.getText(field.key());
                values.put(field.key(), value == null || value.equals(LEAVE_OUT) ? "" : value);
            }
            var typed = form.build(values);
            if (typed.isEmpty()) {
                String missing = form.fields().stream().filter(CommandForm.Field::required)
                        .filter(field -> values.getOrDefault(field.key(), "").isBlank())
                        .map(CommandForm.Field::label).findFirst().orElse("?");
                then(dialogs -> dialogs.form(note, form, backTo, missing));
                return;
            }
            run(typed.get());
        }));
        buttons.add(ActionButton.builder(line("help.type-it", false)).width(BUTTON)
                .action(DialogAction.staticAction(ClickEvent.suggestCommand(form.typeable()))).build());
        show(note.slashed(), body, inputs, buttons, back(backTo), 2);
    }

    /** What a dropdown offers to mean "leave this out" for an optional field. */
    private static final String LEAVE_OUT = "-";

    private DialogInput input(CommandForm.Field field) {
        Component label = Component.text(field.label() + (field.required() ? "" : " (optional)"));
        List<String> choices = field.choices();
        if (field.players()) {
            List<String> online = org.bukkit.Bukkit.getOnlinePlayers().stream().filter(reader::canSee)
                    .map(org.bukkit.entity.Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
            if (!online.isEmpty() && online.size() <= 40) {
                choices = online;
            }
        }
        if (choices.isEmpty()) {
            return DialogInput.text(field.key(), label).width(WIDE_BUTTON).maxLength(256).labelVisible(true).build();
        }
        List<io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        if (!field.required()) {
            entries.add(io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry.create(
                    LEAVE_OUT, Component.text("—"), true));
        }
        boolean first = field.required();
        for (String choice : choices) {
            entries.add(io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry.create(
                    choice, Component.text(choice), first));
            first = false;
        }
        return DialogInput.singleOption(field.key(), label, entries).width(WIDE_BUTTON).labelVisible(true).build();
    }

    /** A command's button: opens it, or — when it takes nothing at all — runs it. */
    private ActionButton commandButton(CommandNote note, int width, Consumer<HelpDialogs> backTo) {
        List<CommandForm> forms = CommandForms.of(note);
        if (forms.size() == 1 && forms.getFirst().fields().isEmpty()) {
            return formButton(note, forms.getFirst(), backTo);
        }
        return button(MINI.deserialize("<" + HelpText.LINK + ">▶ " + Text.literal(note.slashed())),
                MINI.deserialize("<gray>" + Text.literal(note.sentence())), width,
                (response, player) -> then(dialogs -> dialogs.command(note, backTo)));
    }

    /** One form's button: ▶ and its usage. Runs at once when there is nothing to fill in, opens the form otherwise. */
    private ActionButton formButton(CommandNote note, CommandForm form, Consumer<HelpDialogs> backTo) {
        Component label = MINI.deserialize("<" + HelpText.LINK + ">▶ " + Text.literal(form.usage()));
        Component tooltip = MINI.deserialize("<gray>" + Text.literal(note.sentence())
                + (form.note().isEmpty() ? "" : "\n<dark_gray>" + Text.literal(form.note()))
                + "\n\n<dark_gray>" + (form.fields().isEmpty() ? "Click to run it" : "Click to fill it in and run it"));
        if (form.fields().isEmpty()) {
            String typed = form.build(java.util.Map.of()).orElse(form.command());
            return button(label, tooltip, WIDE_BUTTON, (response, player) -> run(typed));
        }
        return button(label, tooltip, WIDE_BUTTON, (response, player) -> then(dialogs -> dialogs.form(note, form, backTo, null)));
    }

    /**
     * Runs a command as the reader, on their own thread, the way typing it would: {@code Player#chat} goes through
     * the command events, so a frozen player's blocks, watched commands and cooldowns all still apply.
     */
    private void run(String typed) {
        Scheduling.entity(desk.plugin(), reader, () -> {
            if (reader.isOnline()) {
                reader.closeDialog();
                reader.chat(typed.startsWith("/") ? typed : "/" + typed);
            }
        });
    }

    /** Every command this reader may use. */
    private List<CommandNote> visibleNotes() {
        return RainsCore.isAvailable() ? RainsCore.get().commands().visibleTo(reader::hasPermission) : List.of();
    }

    /** What the guide knows about some words: the best answers first. */
    public void search(String asked) {
        String words = asked == null ? "" : asked.strip();
        if (words.isEmpty()) {
            show("Search", List.of(DialogBody.plainMessage(line("help.search-empty", false), TEXT_WIDTH)), List.of(), List.of(),
                    back(dialogs -> dialogs.home(false)), 1);
            return;
        }
        Optional<HelpTopic> exact = library.find(words, context);
        if (exact.isPresent()) {
            topic(exact.get());
            return;
        }
        List<HelpLibrary.Hit> hits = library.search(words, context);
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(line("help.search-intro", false, "asked", words), TEXT_WIDTH));
        List<ActionButton> buttons = new ArrayList<>();
        // Commands too: "pay", "sell all", "home" — whatever their name, plugin or sentence says.
        List<CommandNote> commands = visibleNotes().stream()
                .filter(note -> de.raindancer.core.ui.text.WordSearch.matches(words, note.command(), note.sentence(),
                        note.plugin(), String.join(" ", note.options())))
                .limit(6).toList();
        if (hits.isEmpty() && commands.isEmpty()) {
            body.add(DialogBody.plainMessage(line("help.search-nothing", false, "asked", words), TEXT_WIDTH));
            List<String> close = library.closest(words, context);
            if (!close.isEmpty()) {
                body.add(DialogBody.plainMessage(line("help.search-did-you-mean", false, "names",
                        String.join(", ", close)), TEXT_WIDTH));
                for (String name : close) {
                    library.find(name, context).ifPresent(topic -> buttons.add(topicButton(topic, WIDE_BUTTON)));
                }
            }
        } else if (!hits.isEmpty()) {
            body.add(DialogBody.plainMessage(line("help.search-found", false), TEXT_WIDTH));
            for (HelpLibrary.Hit hit : hits.subList(0, Math.min(8, hits.size()))) {
                HelpTopic topic = hit.topic();
                HelpSection section = hit.section();
                Component label = section == null
                        ? MINI.deserialize("<white>" + Text.literal(topic.title()))
                        : MINI.deserialize("<white>" + Text.literal(section.question()));
                Component tooltip = MINI.deserialize("<gray>" + Text.literal(topic.title()) + (section == null ? ""
                        : "\n<dark_gray>" + Text.literal(snippet(HelpLibrary.answer(section, context), words))));
                buttons.add(button(label, tooltip, WIDE_BUTTON, (response, player) -> then(dialogs -> {
                    if (section == null) {
                        dialogs.topic(topic);
                    } else {
                        dialogs.answer(topic, section);
                    }
                })));
            }
        }
        if (!commands.isEmpty()) {
            // A dialog draws its text above its buttons, so a heading here would float away from them: the ▶ says it.
            for (CommandNote note : commands) {
                buttons.add(commandButton(note, WIDE_BUTTON, dialogs -> dialogs.search(words)));
            }
        }
        show("Search", body, List.of(), buttons, back(dialogs -> dialogs.home(false)), 1);
    }

    // ------------------------------------------------------------------ pieces

    /** @param page where the reader is — "The shop" — or null on the greeting; shown at the top of the window */
    private void show(String page, List<DialogBody> body, List<DialogInput> inputs, List<ActionButton> buttons,
                      ActionButton exit, int columns) {
        Component title = MINI.deserialize("<gradient:#C084FC:#7DD3FC>Help</gradient>" + (page == null ? ""
                : " <dark_gray>›</dark_gray> <white>" + Text.literal(page)));
        // A dialog of type multi_action needs at least one button; a window with nothing to press gets the exit.
        List<ActionButton> pressable = buttons.isEmpty() ? List.of(exit) : buttons;
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title).canCloseWithEscape(true).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE).body(body).inputs(inputs).build())
                .type(DialogType.multiAction(pressable).exitAction(buttons.isEmpty() ? null : exit)
                        .columns(columns).build()));
        reader.showDialog(dialog);
    }

    private ActionButton topicButton(HelpTopic topic, int width) {
        return button(MINI.deserialize("<white>" + Text.literal(topic.title())),
                MINI.deserialize("<gray>" + Text.literal(topic.summary())), width,
                (response, player) -> then(dialogs -> dialogs.topic(topic)));
    }

    private ActionButton back(Consumer<HelpDialogs> where) {
        return button(line("help.back", false), null, BUTTON, (response, player) -> then(where));
    }

    private static ActionButton button(Component label, Component tooltip, int width,
                                       io.papermc.paper.registry.data.dialog.action.DialogActionCallback callback) {
        return ActionButton.builder(label).tooltip(tooltip).width(width)
                .action(DialogAction.customClick(callback, CALLBACK)).build();
    }

    /** The next window, built fresh on the reader's own thread — settings may have changed since this one. */
    private void then(Consumer<HelpDialogs> next) {
        Scheduling.entity(desk.plugin(), reader, () -> {
            if (reader.isOnline()) {
                next.accept(forReader(desk, reader));
            }
        });
    }

    private List<CommandNote> notesFor(HelpTopic topic) {
        if (!RainsCore.isAvailable()) {
            return List.of();
        }
        List<CommandNote> notes = new ArrayList<>();
        for (CommandNote note : RainsCore.get().commands().visibleTo(reader::hasPermission)) {
            String first = note.command().split(" ")[0];
            if (topic.commands().contains(first)) {
                notes.add(note);
            }
        }
        return notes;
    }

    private Component render(String text) {
        return HelpText.render(text, context, new ArrayList<>());
    }

    private ItemStack icon(HelpTopic topic) {
        Material material = topic.icon() == null ? null : Material.matchMaterial(topic.icon());
        return new ItemStack(material == null || !material.isItem() ? HelpCategories.icon(topic.category()) : material);
    }

    private Component line(String key, boolean dealt, String... values) {
        Object[] all = new Object[values.length + 6];
        all[0] = "player";
        all[1] = reader.getName();
        all[2] = "server";
        all[3] = context.serverName();
        all[4] = "guide";
        all[5] = desk.settings().guideName();
        System.arraycopy(values, 0, all, 6, values.length);
        if (!RainsCore.isAvailable()) {
            return Component.text(key);
        }
        var messages = RainsCore.get().messages();
        return dealt ? messages.fresh(key, all) : messages.get(key, all);
    }

    private Component plain(String key) {
        return line(key, false);
    }

    private boolean exists(String command) {
        return org.bukkit.Bukkit.getCommandMap().getCommand(command) != null;
    }

    private void voice() {
        String sound = desk.settings().guideVoice();
        if (sound == null || sound.isBlank()) {
            return;
        }
        reader.playSound(reader.getLocation(), sound.strip().toLowerCase(Locale.ROOT), SoundCategory.NEUTRAL, 0.7f, 1.0f);
    }

    /** About 120 characters of an answer around the first word asked for. */
    static String snippet(String answer, String words) {
        String clean = answer.replaceAll("\\s+", " ").strip();
        String first = words.strip().toLowerCase(Locale.ROOT).split("\\s+")[0];
        int at = Math.max(0, clean.toLowerCase(Locale.ROOT).indexOf(first));
        int from = Math.max(0, at - 40);
        int to = Math.min(clean.length(), from + 120);
        return (from > 0 ? "…" : "") + clean.substring(from, to).strip() + (to < clean.length() ? "…" : "");
    }
}
