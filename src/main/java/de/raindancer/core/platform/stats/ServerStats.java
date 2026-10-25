package de.raindancer.core.platform.stats;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Measures the server once a minute — ticks per second, tick time, memory, processor, players — into a day of
 * {@link StatsHistory}, counts the record of players at once and who came today, and keeps all of it in
 * {@code server-stats.yml} so a restart does not wipe the charts. Measured and written off the server's threads.
 */
public final class ServerStats implements Listener {

    private static final LogChannel log = Log.of("stats");
    private static final int SAVE_EVERY_MINUTES = 5;

    private final Plugin plugin;
    private final Server server;
    private final YamlStore file;
    private final LongSupplier clock;
    private final StatsHistory history = new StatsHistory();
    private ScheduledTask timer;
    private int sinceSave;

    public ServerStats(Plugin plugin, Server server, YamlStore file, LongSupplier clock) {
        this.plugin = plugin;
        this.server = server;
        this.file = file;
        this.clock = clock;
    }

    public StatsHistory history() {
        return history;
    }

    public void start() {
        Scheduling.async(plugin, () -> {
            try {
                var yaml = file.read();
                yaml.getStringList("samples").forEach(history::read);
                history.record(new StatsHistory.Record(yaml.getInt("record.players", 0), yaml.getLong("record.at", 0)));
                String day = yaml.getString("today.day", "");
                if (day.equals(LocalDate.now().toString())) {
                    for (String id : yaml.getStringList("today.players")) {
                        try {
                            history.joined(UUID.fromString(id), LocalDate.now());
                        } catch (IllegalArgumentException garbled) {
                            // left out
                        }
                    }
                }
            } catch (RuntimeException unreadable) {
                log.warn("server-stats.yml could not be read; the charts start empty: {}", unreadable.toString());
            }
            timer = Scheduling.asyncTimer(plugin, 5, 60, task -> sample());
        });
    }

    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private void sample() {
        long now = clock.getAsLong();
        Runtime runtime = Runtime.getRuntime();
        int players = server.getOnlinePlayers().size();
        history.add(new StatsHistory.Sample(now / 60_000, tps(), mspt(),
                (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024), runtime.maxMemory() / (1024 * 1024),
                cpu(), players));
        history.online(players, now);
        if (++sinceSave >= SAVE_EVERY_MINUTES) {
            sinceSave = 0;
            save();
        }
    }

    private void save() {
        List<String> lines = history.lines();
        StatsHistory.Record record = history.record();
        LocalDate day = history.day();
        List<String> today = history.today().stream().map(UUID::toString).toList();
        file.write(yaml -> {
            yaml.set("samples", lines);
            yaml.set("record.players", record.players());
            yaml.set("record.at", record.at());
            yaml.set("today.day", day == null ? "" : day.toString());
            yaml.set("today.players", today);
        });
    }

    /** Ticks per second over the last minute; NaN on Folia, which has no single tick. */
    public double tps() {
        try {
            return Math.min(20.0, server.getTPS()[0]);
        } catch (RuntimeException | LinkageError unsupported) {
            return Double.NaN;
        }
    }

    public double mspt() {
        try {
            return server.getAverageTickTime();
        } catch (RuntimeException | LinkageError unsupported) {
            return Double.NaN;
        }
    }

    /** Percent of the machine's processor this server takes; NaN where the JVM cannot say. */
    public static double cpu() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            double load = os.getProcessCpuLoad();
            return load < 0 ? Double.NaN : load * 100;
        }
        return Double.NaN;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        history.joined(event.getPlayer().getUniqueId(), LocalDate.now());
        history.online(server.getOnlinePlayers().size(), clock.getAsLong());
    }
}
