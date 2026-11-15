package de.raindancer.core.world.manage;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.util.Closest;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.chat.ChatButton;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.text.Text;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * {@code /world} — jumping to a loaded world, and throwing one away and making it again.
 *
 * <h2>Why this exists next to {@code /farmworld}</h2>
 * {@code /farmworld} (farmworld-module's, built on this package's {@link WorldRegenerator}
 * underneath) manages a set of worlds tied together by its own bookkeeping — a name, a regeneration
 * schedule, a border. Most of the time an owner just wants "send me to that world" or "wipe that one
 * world and start it over", with no schedule and no set to define first. This is that: two
 * subcommands, no state of its own, {@link WorldRegenerator} doing the actual wipe directly.
 *
 * <h2>Two permissions, not one</h2>
 * Switching worlds is harmless; regenerating one deletes it. A server that wants staff to be able to
 * jump around without trusting them to wipe a world grants one and not the other — the same reasoning
 * farmworld-module's own command splits {@code use} from {@code manage} for. Neither is declared in
 * {@code paper-plugin.yml} — see its own comment for why Core declares no permissions — so both fall
 * back to Bukkit's own default for an unregistered node: an operator has it, nobody else does, until a
 * server owner grants it explicitly.
 */
public final class WorldCommand implements BasicCommand {

    private static final String SWITCH = "rainscore.world.switch";
    private static final String REGEN = "rainscore.world.regen";
    /** How long the confirm button for a regeneration stays live. */
    private static final Duration CONFIRM_FOR = Duration.ofSeconds(30);

    private final WorldRegenerator regenerator;

    public WorldCommand() {
        this(null);
    }

    /**
     * For tests: a fake regenerator that never touches a real world.
     *
     * @param regenerator null asks Core for its own when a regeneration actually runs — built at
     *                    bootstrap, this exists before Core does, and Core's is the one that writes the
     *                    outgoing seed down
     */
    WorldCommand(WorldRegenerator regenerator) {
        this.regenerator = regenerator;
    }

    private WorldRegenerator regenerator() {
        return regenerator != null ? regenerator : RainsCore.get().worldRegenerator();
    }

    private Chat chat() {
        return RainsCore.get().chatFor("Worlds");
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(SWITCH) || sender.hasPermission(REGEN);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!RainsCore.isAvailable()) {
            return;
        }
        if (args.length == 0) {
            usage(sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "switch", "tp" -> doSwitch(sender, args);
            case "regen", "regenerate" -> regen(sender, args);
            default -> {
                // "/world farmworld" — a world's name where the word was expected: go there.
                if (Bukkit.getWorld(args[0]) != null && sender.hasPermission(SWITCH)) {
                    doSwitch(sender, new String[]{"switch", args[0]});
                } else {
                    usage(sender);
                }
            }
        }
    }

    private void usage(CommandSender sender) {
        if (!(sender instanceof Player) || RainsCore.get().buttons() == null) {
            chat().tell(sender, "<gray>/world switch <world>, or /world regen <world> [player]");
            return;
        }
        ChatButtons buttons = RainsCore.get().buttons();
        List<ChatButton> shown = new ArrayList<>();
        if (sender.hasPermission(SWITCH)) {
            shown.add(buttons.label("<gray>[/world switch <world>]")
                    .tooltip("<gray>Go to a loaded world's spawn<newline><dark_gray>Click to type it").suggests("/world switch "));
        }
        if (sender.hasPermission(REGEN)) {
            shown.add(buttons.label("<gray>[/world regen <world> [player]]")
                    .tooltip("<gray>Wipe a world and make it again with a new seed<newline><dark_gray>Click to type it")
                    .suggests("/world regen "));
        }
        chat().raw(sender, buttons.row(shown.toArray(ChatButton[]::new)));
    }

    /** No world by that name: say which are loaded, the likely one first, each a click away. */
    private void noSuchWorld(CommandSender sender, String typed, String subcommand) {
        chat().no(sender, "There is no loaded world called <name>.", Chat.arg("name", typed));
        List<String> loaded = Bukkit.getWorlds().stream().map(World::getName).toList();
        List<String> likely = new ArrayList<>(Closest.to(typed, loaded, 3));
        for (String name : loaded) {
            if (!likely.contains(name)) {
                likely.add(name);
            }
        }
        if (!likely.isEmpty()) {
            noSuchWorldList(sender, likely, subcommand);
        }
    }

    // ------------------------------------------------------------------------ switching

    private void doSwitch(CommandSender sender, String[] args) {
        if (!sender.hasPermission(SWITCH)) {
            chat().no(sender, "Switching worlds needs the permission <perm>. An owner can grant it.",
                    Chat.arg("perm", SWITCH));
            return;
        }
        if (!(sender instanceof Player player)) {
            chat().no(sender, "Only a player can be sent to a world; the console has nowhere to go.");
            return;
        }
        if (args.length < 2) {
            noSuchWorldNamed(sender, "switch");
            return;
        }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            noSuchWorld(sender, args[1], "switch");
            return;
        }
        // Async: the player belongs to the region they are standing in, not the one they are going to,
        // and on Folia a synchronous teleport from either side of that throws.
        player.teleportAsync(world.getSpawnLocation()).thenAccept(moved -> {
            if (moved) {
                chat().ok(player, "Off to <name>.", Chat.arg("name", world.getName()));
            }
        });
    }

    // ------------------------------------------------------------------------ regenerating

    /**
     * Wipes {@code <world>} and makes it again with a fresh seed — see {@link WorldRegenerator} for
     * why no seed is ever set on purpose — and, if a player was named, drops them straight back into
     * the result.
     */
    private void regen(CommandSender sender, String[] args) {
        if (!sender.hasPermission(REGEN)) {
            chat().no(sender, "Regenerating worlds needs the permission <perm>. An owner can grant it.",
                    Chat.arg("perm", REGEN));
            return;
        }
        if (args.length < 2) {
            noSuchWorldNamed(sender, "regen");
            return;
        }
        String name = args[1];
        World world = Bukkit.getWorld(name);
        if (world == null) {
            noSuchWorld(sender, name, "regen");
            return;
        }
        OfflinePlayer toReturn = null;
        if (args.length >= 3) {
            toReturn = Bukkit.getPlayerExact(args[2]);
            if (toReturn == null) {
                chat().no(sender, "<name> is not online. Name somebody who is, or leave it out to send nobody back.",
                        Chat.arg("name", args[2]));
                return;
            }
        }
        OfflinePlayer finalToReturn = toReturn;
        if (sender instanceof Player asking && RainsCore.get().buttons() != null && RainsCore.get().buttons().isClickable()) {
            // Deleting a world is one click too few from a typo: a player confirms, the console does not.
            chat().warn(sender, "Regenerating <name> deletes it and makes a new one with a fresh seed. Everything built there is gone.",
                    Chat.arg("name", name));
            chat().raw(sender, RainsCore.get().buttons().ask(asking.getUniqueId(), CONFIRM_FOR,
                    yes -> regenerate(sender, name, finalToReturn),
                    no -> chat().tell(sender, "<gray>Left <name> as it is.", Chat.arg("name", name))));
            return;
        }
        regenerate(sender, name, finalToReturn);
    }

    private void noSuchWorldNamed(CommandSender sender, String subcommand) {
        chat().tell(sender, "<gray>Which world? <dark_gray>/world " + subcommand + " <world>"
                + (subcommand.equals("regen") ? " [player]" : ""));
        List<String> loaded = Bukkit.getWorlds().stream().map(World::getName).toList();
        if (!loaded.isEmpty()) {
            noSuchWorldList(sender, loaded, subcommand);
        }
    }

    private void noSuchWorldList(CommandSender sender, List<String> names, String subcommand) {
        if (sender instanceof Player && RainsCore.get().buttons() != null) {
            ChatButtons buttons = RainsCore.get().buttons();
            List<ChatButton> options = new ArrayList<>();
            for (String world : names.subList(0, Math.min(8, names.size()))) {
                options.add(buttons.label("<aqua>[" + Text.literal(world) + "]")
                        .tooltip("<gray>/world " + subcommand + " " + Text.literal(world))
                        .suggests("/world " + subcommand + " " + world));
            }
            chat().raw(sender, Text.render("<gray>Loaded: ").append(buttons.row(options.toArray(ChatButton[]::new))));
        } else {
            chat().row(sender, "<gray>Loaded: <names>", Chat.arg("names", String.join(", ", names)));
        }
    }

    private void regenerate(CommandSender sender, String name, OfflinePlayer finalToReturn) {
        World world = Bukkit.getWorld(name);
        if (world == null) {
            chat().no(sender, "<name> is not loaded any more, so nothing was regenerated.", Chat.arg("name", name));
            return;
        }
        chat().tell(sender, "<gray>Regenerating <name>. The server will pause.", Chat.arg("name", name));
        regenerator().regenerate(world, ok -> {
            if (!ok) {
                chat().no(sender, "Something went wrong; the server log has it.");
                return;
            }
            chat().ok(sender, "<name> is new.", Chat.arg("name", name));
            if (finalToReturn instanceof Player player) {
                World fresh = Bukkit.getWorld(name);
                if (fresh != null) {
                    player.teleportAsync(fresh.getSpawnLocation());
                }
            }
        });
    }

    // ------------------------------------------------------------------------ completion

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!RainsCore.isAvailable()) {
            return List.of();
        }
        CommandSender sender = source.getSender();
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>();
            if (sender.hasPermission(SWITCH)) {
                options.add("switch");
            }
            if (sender.hasPermission(REGEN)) {
                options.add("regen");
            }
            return options.stream().filter(word -> word.startsWith(typed)).toList();
        }
        if (args.length == 2) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getWorlds().stream().map(World::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("regen")) {
            String typed = args[2].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        }
        return List.of();
    }
}
