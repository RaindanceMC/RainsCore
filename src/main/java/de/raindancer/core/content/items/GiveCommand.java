package de.raindancer.core.content.items;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.raindancer.core.RainsCore;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /give <player> <item> [amount]} that knows the plugins' items as well as vanilla's.
 *
 * <ul>
 *   <li>{@code /give Ada fishing:rod-void} — made by the plugin's {@link ItemGiver} (else from the item's
 *       {@link CustomItem} definition) and handed over stack by stack;</li>
 *   <li>anything else — {@code diamond 64}, {@code minecraft:stone}, {@code diamond_sword[...]} — goes to vanilla's
 *       {@code minecraft:give} exactly as typed.</li>
 * </ul>
 *
 * <p>Typing helps: players and selectors are vanilla's own argument, the item is suggested by key or by name with its
 * coloured name as the tooltip ({@code void} finds {@code fishing:rod-void}), then one, a stack and more. A mistyped
 * item gets "Did you mean fishing:rod-void?" and a button that runs the corrected give. Vanilla's permission, so
 * nobody gets more or less than before.
 */
public final class GiveCommand {

    public static final String PERMISSION = "minecraft.command.give";
    private static volatile List<String> vanillaItems;
    /** Names for the suggestion tooltips; making an item to read its name is not free. */
    private static final Map<String, Component> NAMES = new ConcurrentHashMap<>();

    private GiveCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("give")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> {
                    context.getSource().getSender().sendMessage(GiveText.usage());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("targets", ArgumentTypes.players())
                        .executes(context -> {
                            context.getSource().getSender().sendMessage(GiveText.usage());
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .suggests(GiveCommand::suggest)
                                .executes(GiveCommand::run)))
                .build();
    }

    private static boolean ours(String key) {
        return ItemGivers.knows(key) || RainsCore.get().items().byKey(key).isPresent();
    }

    private static int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSender sender = context.getSource().getSender();
        String rest = GiveCatalogue.joined(StringArgumentType.getString(context, "item").trim());
        String targets = rangeOf(context, "targets");
        List<String> args = new ArrayList<>();
        args.add(targets);
        args.addAll(Arrays.asList(rest.split("\\s+")));
        switch (GiveRequest.parse(args.toArray(String[]::new), GiveCommand::ours)) {
            case GiveRequest.Vanilla vanilla -> {
                String item = args.get(1);
                Optional<String> meant = isVanilla(item) ? Optional.empty() : GiveCatalogue.didYouMean(item,
                        ourKeys(), vanillaItems(), key -> plainName(key));
                if (meant.isPresent()) {
                    String amount = args.size() > 2 ? " " + args.get(2) : "";
                    String fixed = "/give " + targets + " " + meant.get() + amount;
                    sender.sendMessage(GiveText.didYouMean(meant.get(), RainsCore.get().buttons()
                            .label(GiveText.BUTTON).tooltip("<gray>" + fixed.replace("<", "\\<")).runs(fixed).render()));
                    return 0;
                }
                Bukkit.dispatchCommand(sender, vanilla.command());
            }
            case GiveRequest.Refused refused -> throw new SimpleCommandExceptionType(
                    MessageComponentSerializer.message().serialize(Component.text(refused.why()))).create();
            case GiveRequest.Ours ours -> {
                List<Player> players = context.getArgument("targets", PlayerSelectorArgumentResolver.class)
                        .resolve(context.getSource());
                give(sender, players, ours.key(), ours.amount());
                return players.size();
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    /** A vanilla item, or vanilla's item syntax ({@code diamond_sword[...]}), which only vanilla can judge. */
    private static boolean isVanilla(String item) {
        if (item.contains("[") || item.contains("{") || item.startsWith("#")) {
            return true;
        }
        Material material = Material.matchMaterial(item);
        return material != null && material.isItem();
    }

    private static List<String> ourKeys() {
        TreeSet<String> keys = new TreeSet<>(ItemGivers.keys());
        keys.addAll(RainsCore.get().items().keys());
        return List.copyOf(keys);
    }

    private static String plainName(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(name(key));
    }

    /** The targets exactly as typed, selector and all, for passing a vanilla give on unchanged. */
    private static String rangeOf(CommandContext<CommandSourceStack> context, String name) {
        for (ParsedCommandNode<CommandSourceStack> node : context.getNodes()) {
            if (node.getNode().getName().equals(name)) {
                return node.getRange().get(context.getInput());
            }
        }
        return "@s";
    }

    /** Hands {@code amount} of {@code key} to every player, and tells the giver and each of them. */
    static void give(CommandSender sender, List<Player> players, String key, int amount) {
        Optional<ItemStack> one = make(key, 1);
        if (one.isEmpty()) {
            sender.sendMessage(Component.text(key + " cannot be made right now.",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        Component item = one.get().displayName();
        String from = sender instanceof Player giver ? giver.getName() : null;
        for (Player player : players) {
            for (ItemStack stack : stacks(key, amount, one.get().getMaxStackSize())) {
                player.getInventory().addItem(stack).values()
                        .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
            }
            if (!player.equals(sender)) {
                player.sendMessage(GiveText.received(amount, item, from));
            }
        }
        sender.sendMessage(GiveText.given(amount, item, players.stream().map(Player::getName).toList()));
    }

    /** Made stack by stack: a plugin's giver may write something unique onto each (a net's id, a rod's stats). */
    private static List<ItemStack> stacks(String key, int amount, int perStack) {
        int each = Math.max(1, perStack);
        List<ItemStack> stacks = new ArrayList<>();
        for (int left = amount; left > 0; left -= each) {
            make(key, Math.min(each, left)).ifPresent(stacks::add);
        }
        return stacks;
    }

    private static Optional<ItemStack> make(String key, int amount) {
        Optional<ItemStack> made = ItemGivers.make(key, amount);
        if (made.isPresent()) {
            return made;
        }
        RainsCore core = RainsCore.get();
        return core.items().byKey(key).flatMap(definition -> core.itemFactory().create(definition, amount));
    }

    private static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> context,
                                                          SuggestionsBuilder builder) {
        String typed = builder.getRemaining();
        int space = typed.indexOf(' ');
        if (space >= 0) {
            String key = typed.substring(0, space).toLowerCase(Locale.ROOT);
            if (ours(key) && typed.indexOf(' ', space + 1) < 0) {
                SuggestionsBuilder amounts = builder.createOffset(builder.getStart() + space + 1);
                int stack = make(key, 1).map(ItemStack::getMaxStackSize).orElse(64);
                amounts.suggest(1, tooltip("one"));
                if (stack > 1) {
                    amounts.suggest(stack, tooltip("a stack"));
                }
                amounts.suggest(stack * 9 * 4, tooltip("an inventory full"));
                return amounts.buildFuture();
            }
            return builder.buildFuture();
        }
        List<String> keys = ourKeys();
        // By key first, then by name: "void" finds fishing:rod-void and fishing:bait-voidlure, "fish" every fish.
        TreeSet<String> found = new TreeSet<>();
        for (String key : keys) {
            if (key.startsWith(typed.toLowerCase(Locale.ROOT))) {
                found.add(key);
            }
        }
        if (!typed.isBlank() && !typed.contains(":")) {
            found.addAll(GiveCatalogue.filter(keys, null, typed, GiveCommand::plainName));
        }
        for (String key : found) {
            builder.suggest(key, MessageComponentSerializer.message().serialize(name(key)));
        }
        for (String suggestion : GiveRequest.suggest(typed, List.of(), vanillaItems())) {
            builder.suggest(suggestion);
        }
        return builder.buildFuture();
    }

    private static com.mojang.brigadier.Message tooltip(String text) {
        return MessageComponentSerializer.message().serialize(Component.text(text));
    }

    private static Component name(String key) {
        return NAMES.computeIfAbsent(key, any -> make(key, 1).map(stack -> stack.effectiveName())
                .orElse(Component.text(key)));
    }

    private static List<String> vanillaItems() {
        List<String> known = vanillaItems;
        if (known == null) {
            known = Arrays.stream(Material.values()).filter(material -> !material.isLegacy() && material.isItem()
                    && material != Material.AIR).map(material -> material.getKey().toString()).sorted().toList();
            vanillaItems = known;
        }
        return known;
    }
}
