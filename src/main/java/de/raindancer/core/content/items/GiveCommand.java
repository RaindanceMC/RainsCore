package de.raindancer.core.content.items;

import de.raindancer.core.RainsCore;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * {@code /give} that knows the plugins' items as well as vanilla's: {@code /give Ada fishing:rod-void} makes the rod
 * the fishing plugin makes ({@link ItemGivers}, else the item's {@link CustomItem} definition); anything else —
 * {@code diamond}, {@code minecraft:stone}, {@code diamond_sword[...]} — goes to {@code minecraft:give} as typed.
 * The same permission as vanilla's, so nobody gets more or less than before.
 */
public final class GiveCommand implements BasicCommand {

    public static final String PERMISSION = "minecraft.command.give";
    private static volatile List<String> vanillaItems;

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(PERMISSION);
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    private static boolean ours(String key) {
        return ItemGivers.knows(key) || RainsCore.get().items().byKey(key).isPresent();
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        switch (GiveRequest.parse(args, GiveCommand::ours)) {
            case GiveRequest.Vanilla vanilla -> Bukkit.dispatchCommand(sender, vanilla.command());
            case GiveRequest.Refused refused -> sender.sendMessage(Component.text(refused.why(), NamedTextColor.RED));
            case GiveRequest.Ours ours -> give(sender, ours);
        }
    }

    private static void give(CommandSender sender, GiveRequest.Ours request) {
        List<Player> players = new ArrayList<>();
        try {
            for (Entity entity : Bukkit.selectEntities(sender, request.target())) {
                if (entity instanceof Player player) {
                    players.add(player);
                }
            }
        } catch (IllegalArgumentException badSelector) {
            sender.sendMessage(Component.text("No player or selector: " + request.target(), NamedTextColor.RED));
            return;
        }
        if (players.isEmpty()) {
            sender.sendMessage(Component.translatable("argument.entity.notfound.player", NamedTextColor.RED));
            return;
        }
        Optional<ItemStack> one = make(request.key(), 1);
        if (one.isEmpty()) {
            sender.sendMessage(Component.text(request.key() + " cannot be made right now.", NamedTextColor.RED));
            return;
        }
        for (Player player : players) {
            for (ItemStack stack : stacks(request.key(), request.amount(), one.get().getMaxStackSize())) {
                player.getInventory().addItem(stack).values()
                        .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
            }
        }
        Component count = Component.text(request.amount());
        Component item = one.get().displayName();
        sender.sendMessage(players.size() == 1
                ? Component.translatable("commands.give.success.single", count, item, players.getFirst().name())
                : Component.translatable("commands.give.success.multiple", count, item,
                Component.text(players.size())));
    }

    /** Made stack by stack: a plugin's own giver may write something unique onto each (a net's id, a rod's stats). */
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

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
            List<String> names = new ArrayList<>(List.of("@a", "@p", "@r", "@s"));
            Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
            return names.stream().filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(typed)).toList();
        }
        if (args.length == 2) {
            TreeSet<String> ours = new TreeSet<>(ItemGivers.keys());
            ours.addAll(RainsCore.get().items().keys());
            return GiveRequest.suggest(args[1], ours, vanillaItems());
        }
        return args.length == 3 ? List.of("1", "16", "64") : List.of();
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
