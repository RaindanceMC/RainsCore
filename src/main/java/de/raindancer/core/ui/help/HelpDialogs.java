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
        show(body, List.of(DialogInput.text(ASK, line("help.ask-label", false)).width(WIDE_BUTTON)
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
        show(List.of(DialogBody.item(new ItemStack(HelpCategories.icon(category))).showTooltip(false)
                        .description(DialogBody.plainMessage(line("help.category-intro", false, "category", category),
                                TEXT_WIDTH - 40)).build()),
                List.of(), buttons, back(dialogs -> dialogs.home(false)), 2);
    }

    /** A subject: the guide's opening words, and every question this reader may ask about it. */
    public void topic(HelpTopic topic) {
        List<DialogBody> body = new ArrayList<>();
        Component opening = render(topic.opening());
        body.add(DialogBody.item(icon(topic)).showTooltip(false)
                .description(DialogBody.plainMessage(MINI.deserialize("<bold><gradient:#C084FC:#7DD3FC>"
                        + Text.literal(topic.title()) + "</gradient></bold>\n").append(opening), TEXT_WIDTH - 40))
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
        show(body, List.of(), buttons, back(dialogs -> {
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
        buttons.add(button(line("help.something-else", false), null, WIDE_BUTTON,
                (response, player) -> then(dialogs -> dialogs.home(false))));
        show(body, List.of(), buttons, back(dialogs -> dialogs.topic(topic)), 1);
    }

    /** The commands of a subject this reader may use — each a button that puts it into the chat bar. */
    public void commands(HelpTopic topic) {
        List<CommandNote> notes = notesFor(topic);
        List<ActionButton> buttons = new ArrayList<>();
        for (CommandNote note : notes) {
            StringBuilder tooltip = new StringBuilder("<gray>" + Text.literal(note.sentence()));
            for (String option : note.options()) {
                tooltip.append("\n<dark_gray>").append(Text.literal(note.slashed() + " " + option));
            }
            buttons.add(ActionButton.builder(MINI.deserialize("<#7DD3FC>" + Text.literal(note.slashed())))
                    .tooltip(MINI.deserialize(tooltip.toString())).width(BUTTON)
                    .action(DialogAction.staticAction(ClickEvent.suggestCommand(note.slashed() + " "))).build());
        }
        String intro = notes.isEmpty() ? "help.commands-none" : "help.commands-intro";
        show(List.of(DialogBody.plainMessage(line(intro, false, "title", topic.title()), TEXT_WIDTH)), List.of(),
                buttons, back(dialogs -> dialogs.topic(topic)), 2);
    }

    /** What the guide knows about some words: the best answers first. */
    public void search(String asked) {
        String words = asked == null ? "" : asked.strip();
        if (words.isEmpty()) {
            show(List.of(DialogBody.plainMessage(line("help.search-empty", false), TEXT_WIDTH)), List.of(), List.of(),
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
        if (hits.isEmpty()) {
            body.add(DialogBody.plainMessage(line("help.search-nothing", false, "asked", words), TEXT_WIDTH));
            List<String> close = library.closest(words, context);
            if (!close.isEmpty()) {
                body.add(DialogBody.plainMessage(line("help.search-did-you-mean", false, "names",
                        String.join(", ", close)), TEXT_WIDTH));
                for (String name : close) {
                    library.find(name, context).ifPresent(topic -> buttons.add(topicButton(topic, WIDE_BUTTON)));
                }
            }
        } else {
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
        show(body, List.of(), buttons, back(dialogs -> dialogs.home(false)), 1);
    }

    // ------------------------------------------------------------------ pieces

    private void show(List<DialogBody> body, List<DialogInput> inputs, List<ActionButton> buttons, ActionButton exit,
                      int columns) {
        Component title = MINI.deserialize("<gradient:#C084FC:#7DD3FC>" + Text.literal(desk.settings().guideName())
                + "</gradient>");
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
