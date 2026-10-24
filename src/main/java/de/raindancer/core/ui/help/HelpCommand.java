package de.raindancer.core.ui.help;

import de.raindancer.core.RainsCore;
import de.raindancer.core.social.desk.ServiceDesk;
import de.raindancer.core.social.desk.ServiceDesks;
import de.raindancer.core.world.npc.Attendants;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /help} — the guide.
 *
 * <pre>
 * /help                      the greeting: subjects, and a box for a question
 * /help economy              a subject (by id, alias, a command it covers — /help sell — or its title)
 * /help economy 2            one answer, by number or by words of its question
 * /help search how to sell   what the guide knows about some words
 * /help … chat               the same in chat, for anybody whose client shows no dialogs
 * /help check | reload       for staff: what is wrong with the guide here; read every file again
 * /help guide place [mob]    for staff: a guide standing where you are · guide remove
 * </pre>
 *
 * Nothing is held: registered from a bootstrapper, before Core exists. Everything is looked up when it runs.
 */
public final class HelpCommand implements BasicCommand {

    /** What {@code /help} says about itself — also how {@link HelpTakeover} recognises it among the server's commands. */
    public static final String DESCRIPTION = "Ask the guide: every feature here, explained as it is set up.";

    /** {@code /help check} and {@code /help reload}. Undeclared Core nodes are operators' only. */
    public static final String MANAGE = "rainscore.help.manage";
    /** Placing and removing guides. */
    public static final String GUIDE = "rainscore.help.guide";

    @Override
    public boolean canUse(CommandSender sender) {
        return true;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Optional<HelpDesk> found = HelpDesk.current();
        if (found.isEmpty() || !RainsCore.isAvailable()) {
            sender.sendRichMessage("<gray>The guide is not here yet — try again in a moment.");
            return;
        }
        HelpDesk desk = found.get();
        List<String> words = new ArrayList<>(Arrays.asList(args));
        boolean chat = !(sender instanceof Player) || inChat(desk, sender);
        if (!words.isEmpty() && words.getLast().equalsIgnoreCase("chat")) {
            words.removeLast();
            chat = true;
        }
        String first = words.isEmpty() ? "" : words.getFirst().toLowerCase(Locale.ROOT);

        if (first.equals("check") && sender.hasPermission(MANAGE)) {
            check(desk, sender);
            return;
        }
        if (first.equals("reload") && sender.hasPermission(MANAGE)) {
            desk.reloadAsync(library -> say(sender, "help.reloaded", "topics", String.valueOf(library.all().size()),
                    "problems", String.valueOf(library.problems().size())));
            return;
        }
        if (first.equals("guide") && sender.hasPermission(GUIDE) && sender instanceof Player player) {
            guide(desk, player, words.subList(1, words.size()));
            return;
        }

        if (words.isEmpty()) {
            if (chat) {
                new HelpChat(desk, sender).home();
            } else {
                HelpDialogs.forReader(desk, (Player) sender).home(true);
            }
            return;
        }
        if (first.equals("search")) {
            search(desk, sender, String.join(" ", words.subList(1, words.size())), chat);
            return;
        }

        HelpContext context = desk.contextFor(sender);
        HelpLibrary library = desk.library();
        String typed = String.join(" ", words);
        // "/help shop 2" is the second question of the shop, even though "shop 2" alone would find the shop too.
        if (words.size() == 2 && words.get(1).chars().allMatch(Character::isDigit)) {
            Optional<HelpTopic> numbered = library.find(words.getFirst(), context);
            Optional<HelpSection> section = numbered.flatMap(subject -> HelpLibrary.section(subject, words.get(1), context));
            if (section.isPresent()) {
                open(desk, sender, numbered.get(), section.get(), chat);
                return;
            }
            if (numbered.isPresent()) {
                // A number past the end (or a question switched off here): the topic, so its questions show.
                open(desk, sender, numbered.get(), null, chat);
                return;
            }
        }
        Optional<HelpTopic> topic = library.find(typed, context);
        if (topic.isPresent()) {
            open(desk, sender, topic.get(), null, chat);
            return;
        }
        if (words.size() > 1) {
            Optional<HelpTopic> byFirst = library.find(words.getFirst(), context);
            if (byFirst.isPresent()) {
                Optional<HelpSection> section = HelpLibrary.section(byFirst.get(),
                        String.join(" ", words.subList(1, words.size())), context);
                if (section.isPresent()) {
                    open(desk, sender, byFirst.get(), section.get(), chat);
                    return;
                }
            }
        }
        // A real topic, switched off on this server — said so, rather than "I know nothing". Not for topics the
        // reader may not read: those stay unmentioned, so a player never learns the staff pages' names.
        Optional<HelpTopic> off = library.byId(typed)
                .filter(hidden -> hidden.permission() == null || sender.hasPermission(hidden.permission()));
        if (off.isPresent()) {
            say(sender, "help.switched-off", "title", off.get().title());
            return;
        }
        // Not a subject: then it was a question.
        search(desk, sender, typed, chat);
    }

    private static void open(HelpDesk desk, CommandSender sender, HelpTopic topic, HelpSection section, boolean chat) {
        if (chat) {
            HelpChat speaking = new HelpChat(desk, sender);
            if (section == null) {
                speaking.topic(topic);
            } else {
                speaking.answer(topic, section);
            }
            return;
        }
        HelpDialogs dialogs = HelpDialogs.forReader(desk, (Player) sender);
        if (section == null) {
            dialogs.topic(topic);
        } else {
            dialogs.answer(topic, section);
        }
    }

    private static void search(HelpDesk desk, CommandSender sender, String words, boolean chat) {
        if (chat) {
            new HelpChat(desk, sender).search(words);
        } else {
            HelpDialogs.forReader(desk, (Player) sender).search(words);
        }
    }

    private static void check(HelpDesk desk, CommandSender sender) {
        List<String> problems = desk.check(sender);
        if (problems.isEmpty()) {
            say(sender, "help.check-clean", "topics", String.valueOf(desk.library().all().size()));
            return;
        }
        say(sender, "help.check-header", "count", String.valueOf(problems.size()));
        for (String problem : problems) {
            say(sender, "help.check-line", "problem", problem);
        }
    }

    private static void guide(HelpDesk desk, Player player, List<String> words) {
        String action = words.isEmpty() ? "" : words.getFirst().toLowerCase(Locale.ROOT);
        switch (action) {
            case "place" -> {
                String type = words.size() > 1 ? words.get(1) : "";
                Optional<Entity> placed = new HelpGuide(desk).place(player, type);
                if (placed.isEmpty()) {
                    say(player, "help.guide-bad-type", "type", type);
                } else {
                    say(player, "help.guide-placed", "guide", desk.settings().guideName());
                }
            }
            case "remove" -> {
                Optional<Entity> nearest = Attendants.nearest(player, 4,
                        entity -> ServiceDesks.deskOf(entity).filter(ServiceDesk.GUIDE::equals).isPresent());
                nearest.ifPresent(Entity::remove);
                say(player, nearest.isPresent() ? "help.guide-removed" : "help.guide-none-near");
            }
            default -> say(player, "help.usage-guide");
        }
    }

    private static boolean inChat(HelpDesk desk, CommandSender sender) {
        return sender instanceof Player player && desk.settings().bedrockInChat() && isBedrock(player);
    }

    /** Floodgate gives Bedrock players ids whose upper half is zero; no Java account has one. */
    static boolean isBedrock(Player player) {
        return player.getUniqueId().getMostSignificantBits() == 0;
    }

    private static void say(CommandSender sender, String key, Object... values) {
        RainsCore.get().messages().send(sender, key, values);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        Optional<HelpDesk> found = HelpDesk.current();
        if (found.isEmpty() || args.length > 3) {
            return List.of();
        }
        CommandSender sender = source.getSender();
        HelpDesk desk = found.get();
        HelpContext context = desk.contextFor(sender);
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            options.add("search");
            if (sender.hasPermission(MANAGE)) {
                options.addAll(List.of("check", "reload"));
            }
            if (sender.hasPermission(GUIDE)) {
                options.add("guide");
            }
            for (HelpTopic topic : desk.library().visibleTo(context)) {
                options.add(topic.id());
                options.addAll(topic.aliases());
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("guide") && sender.hasPermission(GUIDE)) {
            options.addAll(List.of("place", "remove"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("guide") && args[1].equalsIgnoreCase("place")) {
            options.add("mannequin");
            Registry.ENTITY_TYPE.forEach(type -> {
                if (type.getEntityClass() != null && org.bukkit.entity.Mob.class.isAssignableFrom(type.getEntityClass())) {
                    options.add(type.getKey().getKey());
                }
            });
        } else if (args.length == 2) {
            desk.library().find(args[0], context).ifPresent(topic -> {
                int count = HelpLibrary.sectionsFor(topic, context).size();
                for (int number = 1; number <= count; number++) {
                    options.add(String.valueOf(number));
                }
                options.add("chat");
            });
        }
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).distinct().toList();
    }
}
