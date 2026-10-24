package de.raindancer.core.ui.help;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.command.CommandNote;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The guide in chat: for the console, which has no windows, for Bedrock players on servers whose Geyser shows no
 * dialogs, and for anybody who types {@code chat} after a topic. The same words as the dialogs, each question a
 * link to its answer.
 */
final class HelpChat {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final HelpDesk desk;
    private final CommandSender reader;
    private final HelpContext context;
    private final HelpLibrary library;

    HelpChat(HelpDesk desk, CommandSender reader) {
        this.desk = desk;
        this.reader = reader;
        this.context = desk.contextFor(reader);
        this.library = desk.library();
    }

    void home() {
        say(line("help.chat-header", "title", "Help"));
        say(line("help.greeting"));
        for (Map.Entry<String, List<HelpTopic>> shelf : library.byCategory(context).entrySet()) {
            List<Component> links = new ArrayList<>();
            for (HelpTopic topic : shelf.getValue()) {
                links.add(link(topic.title(), "/help " + topic.id() + " chat", topic.summary()));
            }
            say(line("help.chat-index-category", "category", shelf.getKey(), "topics",
                    Component.join(JoinConfiguration.separator(Component.text(", ")), links)));
        }
    }

    void topic(HelpTopic topic) {
        say(line("help.chat-header", "title", topic.title()));
        say(HelpText.render(topic.opening(), context, new ArrayList<>()));
        if (topic.generated()) {
            say(line("help.generated-note"));
        }
        int number = 0;
        for (HelpSection section : HelpLibrary.sectionsFor(topic, context)) {
            number++;
            say(line("help.chat-question", "number", String.valueOf(number), "question", section.question())
                    .clickEvent(ClickEvent.runCommand("/help " + topic.id() + " " + number + " chat"))
                    .hoverEvent(HoverEvent.showText(Component.text("Click for the answer"))));
        }
        List<CommandNote> notes = notesFor(topic);
        if (!notes.isEmpty()) {
            say(line("help.which-commands"));
            for (CommandNote note : notes) {
                say(MINI.deserialize("  <#7DD3FC>" + Text.literal(note.slashed()) + "</#7DD3FC> <gray>— "
                        + Text.literal(note.sentence())).clickEvent(ClickEvent.suggestCommand(note.slashed() + " ")));
            }
        }
    }

    void answer(HelpTopic topic, HelpSection section) {
        say(line("help.chat-header", "title", section.question()));
        say(HelpText.render(section.text(), context, new ArrayList<>()));
        List<HelpSection> asked = HelpLibrary.sectionsFor(topic, context);
        int index = asked.indexOf(section);
        if (index >= 0 && index + 1 < asked.size()) {
            say(line("help.next-question", "question", asked.get(index + 1).question())
                    .clickEvent(ClickEvent.runCommand("/help " + topic.id() + " " + (index + 2) + " chat")));
        }
        say(line("help.back").clickEvent(ClickEvent.runCommand("/help " + topic.id() + " chat")));
    }

    void search(String words) {
        List<HelpLibrary.Hit> hits = library.search(words, context);
        say(line("help.search-intro", "asked", words));
        if (hits.isEmpty()) {
            say(line("help.search-nothing", "asked", words));
            List<String> close = library.closest(words, context);
            if (!close.isEmpty()) {
                say(line("help.search-did-you-mean", "names", String.join(", ", close)));
            }
            return;
        }
        for (HelpLibrary.Hit hit : hits.subList(0, Math.min(8, hits.size()))) {
            HelpTopic topic = hit.topic();
            if (hit.section() == null) {
                say(Component.text(" ").append(link(topic.title(), "/help " + topic.id() + " chat", topic.summary())));
            } else {
                int number = HelpLibrary.sectionsFor(topic, context).indexOf(hit.section()) + 1;
                say(Component.text(" ").append(link(hit.section().question(),
                        "/help " + topic.id() + " " + number + " chat", topic.title())));
            }
        }
    }

    private List<CommandNote> notesFor(HelpTopic topic) {
        if (!RainsCore.isAvailable()) {
            return List.of();
        }
        return RainsCore.get().commands().visibleTo(reader::hasPermission).stream()
                .filter(note -> topic.commands().contains(note.command().split(" ")[0])).toList();
    }

    private static Component link(String label, String command, String hover) {
        return MINI.deserialize("<" + HelpText.LINK + ">" + Text.literal(label))
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover == null ? label : hover)));
    }

    private Component line(String key, Object... values) {
        Object[] all = new Object[values.length + 6];
        all[0] = "player";
        all[1] = context.readerName();
        all[2] = "server";
        all[3] = context.serverName();
        all[4] = "guide";
        all[5] = desk.settings().guideName();
        System.arraycopy(values, 0, all, 6, values.length);
        var messages = RainsCore.get().messages();
        return key.equals("help.greeting") ? messages.fresh(key, all) : messages.get(key, all);
    }

    private void say(Component line) {
        reader.sendMessage(line);
    }
}
