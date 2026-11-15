package de.raindancer.core.moderation.maintenance;

import de.raindancer.core.RainsCore;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /maintenance} — the state with no arguments, otherwise:
 *
 * <pre>
 * /maintenance on [reason…]     closed to joins at once; after a 20 s countdown everybody online who is
 *                               neither op nor on the list is sent off
 * /maintenance on update [min]  the same with a 60 s countdown, telling everybody it is an update and when to
 *                               try again (if not said: the median of how long the last updates took from on
 *                               to off, 3 min until one was measured)
 * /maintenance off
 * /maintenance add &lt;player&gt;     on the list (kept while maintenance is off)
 * /maintenance remove &lt;player&gt;
 * /maintenance list
 * </pre>
 */
public final class MaintenanceCommand implements BasicCommand {

    public static final String PERMISSION = "rainscore.maintenance";
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final List<String> WORDS = List.of("on", "off", "add", "remove", "list");
    public static final int UPDATE_MINUTES = 3;
    /** The one countdown running, so typing "on" twice does not start two. */
    private static final java.util.concurrent.atomic.AtomicReference<io.papermc.paper.threadedregions.scheduler.ScheduledTask>
            COUNTDOWN = new java.util.concurrent.atomic.AtomicReference<>();

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(PERMISSION);
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Maintenance maintenance = RainsCore.get().maintenance();
        String word = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (word) {
            case "on" -> {
                String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                long expected = 0;
                String guessed = "";
                if (args.length >= 2 && args[1].equalsIgnoreCase(MaintenanceText.UPDATE)) {
                    int minutes = MaintenanceText.updateMinutes(maintenance.expectedUpdateMillis(UPDATE_MINUTES * 60_000L));
                    if (args.length < 3) {
                        int measured = maintenance.updateTook().size();
                        guessed = " Back in about " + minutes + " min, players are told ("
                                + (measured == 0 ? "nothing measured yet" : "median of the last " + measured
                                + (measured == 1 ? " update" : " updates")) + ").";
                    } else {
                        try {
                            minutes = Integer.parseInt(args[2]);
                        } catch (NumberFormatException notANumber) {
                            minutes = -1;
                        }
                        if (minutes < 1 || minutes > 240 || args.length > 3) {
                            say(sender, "<red>/maintenance on update [minutes, 1 to 240]");
                            return;
                        }
                    }
                    reason = MaintenanceText.UPDATE;
                    expected = minutes * 60_000L;
                }
                boolean saved = maintenance.turnOn(reason, MaintenanceText.graceMillis(reason), expected);
                say(sender, "<gold>Maintenance mode is on.</gold> <gray>Only ops and the maintenance list can join; "
                        + "everybody else is sent off in " + maintenance.secondsLeft() + " s." + guessed);
                warnIfNotSaved(sender, saved);
                startCountdown(maintenance);
                audit(sender, "turned maintenance mode on", reason);
            }
            case "off" -> {
                boolean wasCounting = maintenance.isCountingDown();
                warnIfNotSaved(sender, maintenance.turnOff());
                stopCountdown();
                if (wasCounting) {
                    broadcast("<green>Maintenance was called off.</green> <gray>Carry on!");
                }
                say(sender, "<green>Maintenance mode is off.</green> <gray>Everybody can join again.");
                audit(sender, "turned maintenance mode off", "");
            }
            case "add" -> {
                if (args.length < 2) {
                    say(sender, "<red>/maintenance add <player>");
                    return;
                }
                add(sender, maintenance, args[1]);
            }
            case "remove" -> {
                if (args.length < 2) {
                    say(sender, "<red>/maintenance remove <player>");
                    return;
                }
                Optional<UUID> id = listed(maintenance, args[1]);
                if (id.isEmpty() || !maintenance.disallow(id.get())) {
                    say(sender, "<red>" + MINI.escapeTags(args[1]) + " is not on the maintenance list.");
                    return;
                }
                say(sender, "<green>" + MINI.escapeTags(args[1]) + " is off the maintenance list.");
                if (maintenance.isOn() && !maintenance.isCountingDown()) {
                    sendOff(maintenance);
                }
                audit(sender, "took somebody off the maintenance list", args[1]);
            }
            case "list" -> {
                Map<UUID, String> allowed = maintenance.allowed();
                say(sender, allowed.isEmpty()
                        ? "<gray>The maintenance list is empty; only ops can join during maintenance."
                        : "<gray>Maintenance list (" + allowed.size() + "): <white>"
                        + MINI.escapeTags(String.join(", ", allowed.values())));
            }
            default -> status(sender, maintenance);
        }
    }

    private static void status(CommandSender sender, Maintenance maintenance) {
        if (!maintenance.isOn()) {
            say(sender, "<gray>Maintenance mode is <green>off</green>. <white>/maintenance on [reason]</white> to close the server.");
        } else {
            Duration on = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - maintenance.since()));
            say(sender, "<gray>Maintenance mode is <gold>on</gold> (for " + on.toHours() + " h " + on.toMinutesPart()
                    + " min)" + (maintenance.reason().isBlank() ? "" : ": <white>" + MINI.escapeTags(maintenance.reason()))
                    + "<gray>. " + maintenance.allowed().size() + " on the list."
                    + (maintenance.isCountingDown() ? " Sending off the rest in " + maintenance.secondsLeft() + " s." : "")
                    + " <white>/maintenance off</white> opens it.");
        }
        maintenance.problems().forEach(problem -> say(sender, "<red>maintenance.yml: " + MINI.escapeTags(problem)));
    }

    private static void add(CommandSender sender, Maintenance maintenance, String name) {
        Optional<OfflinePlayer> known = PlayerTargets.byRealName(Bukkit.getServer(), name);
        if (known.isPresent() && known.get().getUniqueId() != null) {
            added(sender, maintenance, known.get().getUniqueId(), known.get().getName() == null ? name : known.get().getName());
            return;
        }
        // Never on this server: asked of Mojang, off the server's threads. Bedrock players have to have joined once.
        Bukkit.createProfile(name).update().whenComplete((profile, failure) -> {
            if (failure != null || profile == null || profile.getId() == null) {
                say(sender, "<red>No player called " + MINI.escapeTags(name) + ", not on this server and not at Mojang. "
                        + "Bedrock players need to have joined once.");
                return;
            }
            added(sender, maintenance, profile.getId(), profile.getName() == null ? name : profile.getName());
        });
    }

    private static void added(CommandSender sender, Maintenance maintenance, UUID id, String name) {
        warnIfNotSaved(sender, maintenance.allow(id, name));
        say(sender, "<green>" + MINI.escapeTags(name) + " is on the maintenance list"
                + (maintenance.isOn() ? " and can join now." : " for the next maintenance."));
        audit(sender, "put somebody on the maintenance list", name);
    }

    private static Optional<UUID> listed(Maintenance maintenance, String name) {
        return maintenance.allowed().entrySet().stream()
                .filter(entry -> entry.getValue().equalsIgnoreCase(name) || entry.getKey().toString().equalsIgnoreCase(name))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private static void startCountdown(Maintenance maintenance) {
        Plugin core = Bukkit.getPluginManager().getPlugin("RainsCore");
        long[] lastSaid = {-1};
        var task = Scheduling.globalTimer(core, 1L, 20L, self -> {
            if (!maintenance.isCountingDown()) {
                self.cancel();
                COUNTDOWN.compareAndSet(self, null);
                return;
            }
            if (maintenance.kickDue()) {
                sendOff(maintenance);
                self.cancel();
                COUNTDOWN.compareAndSet(self, null);
                return;
            }
            long left = maintenance.secondsLeft();
            if (left == lastSaid[0]) {
                return;
            }
            boolean first = lastSaid[0] == -1;
            long mark = MaintenanceText.crossed(lastSaid[0], left);
            lastSaid[0] = left;
            var said = mark < 0 ? null : MaintenanceText.countdown(maintenance.reason(), mark, first);
            if (said == null) {
                return;
            }
            for (Player online : Bukkit.getOnlinePlayers()) {
                Scheduling.entity(core, online, () -> online.sendMessage(said));
            }
        });
        var before = COUNTDOWN.getAndSet(task);
        if (before != null) {
            before.cancel();
        }
    }

    private static void stopCountdown() {
        var running = COUNTDOWN.getAndSet(null);
        if (running != null) {
            running.cancel();
        }
    }

    private static void broadcast(String miniMessage) {
        Plugin core = Bukkit.getPluginManager().getPlugin("RainsCore");
        for (Player online : Bukkit.getOnlinePlayers()) {
            Scheduling.entity(core, online, () -> say(online, miniMessage));
        }
    }

    /** Everybody online who may not stay; how many. */
    private static int sendOff(Maintenance maintenance) {
        Plugin core = Bukkit.getPluginManager().getPlugin("RainsCore");
        int sent = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!maintenance.mayJoin(online.getUniqueId(), online.isOp())) {
                sent++;
                Scheduling.entity(core, online, () -> online.kick(MaintenanceText.closed(maintenance.reason(), maintenance.backAt(), System.currentTimeMillis())));
            }
        }
        return sent;
    }

    private static void warnIfNotSaved(CommandSender sender, boolean saved) {
        if (!saved) {
            say(sender, "<yellow>maintenance.yml could not be written. This lasts until the next restart. The console says why.");
        }
    }

    private static void audit(CommandSender sender, String what, String detail) {
        AuditEntry.Builder entry = AuditEntry.of("core", what).saying(detail);
        if (sender instanceof Player player) {
            entry.by(player.getUniqueId(), player.getName());
        }
        RainsCore.get().audit().record(entry);
    }

    private static void say(CommandSender sender, String miniMessage) {
        sender.sendMessage(MINI.deserialize(miniMessage));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return WORDS.stream().filter(word -> word.startsWith(typed)).toList();
        }
        String last = args[args.length - 1];
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on" -> args.length == 2 && MaintenanceText.UPDATE.startsWith(last.toLowerCase(Locale.ROOT))
                    ? List.of(MaintenanceText.UPDATE) : List.of();
            case "add" -> args.length == 2 ? PlayerTargets.suggestKnown(Bukkit.getServer(), last, player -> true) : List.of();
            case "remove" -> args.length == 2 ? RainsCore.get().maintenance().allowed().values().stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(last.toLowerCase(Locale.ROOT))).toList() : List.of();
            default -> List.of();
        };
    }
}
