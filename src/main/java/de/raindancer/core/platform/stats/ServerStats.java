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
 *
 * <p>Players also come from the audit journal's joins and quits — the history the panel draws, network-wide and going
 * back to the journal's first line: the record is read from all of it at start, the last day's chart and today's
 * visitors from its last day and a half, every minute. Where the journal is off, the own samples stand in.
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
    private final java.util.function.Supplier<de.raindancer.core.moderation.audit.Audit> audit;
    private final List<PresenceTimeline.Event> events = new java.util.ArrayList<>();
    /** Journal lines already read, by id, with when they were — so a line read twice counts once. */
    private final java.util.Map<Long, Long> seen = new java.util.HashMap<>();
    private volatile PresenceTimeline journal;
    /** The rolling window: a day, plus the longest a session may go without a word, so nobody on is missed. */
    private static final long WINDOW = (24 + PresenceTimeline.STALE_HOURS + 2) * 3_600_000L;

    public ServerStats(Plugin plugin, Server server, YamlStore file, LongSupplier clock) {
        this(plugin, server, file, clock, () -> null);
    }

    /** @param audit the journal joins and quits are read from; null where there is none */
    public ServerStats(Plugin plugin, Server server, YamlStore file, LongSupplier clock,
                       java.util.function.Supplier<de.raindancer.core.moderation.audit.Audit> audit) {
        this.audit = audit;
        this.plugin = plugin;
        this.server = server;
        this.file = file;
        this.clock = clock;
    }

    public StatsHistory history() {
        return history;
    }

    /** Players on at once as the journal tells it, network-wide; empty while it is off or not read yet. */
    public java.util.Optional<PresenceTimeline> journal() {
        PresenceTimeline held = journal;
        return held == null || held.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(held);
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
            readJournal(true);
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
        readJournal(false);
        if (++sinceSave >= SAVE_EVERY_MINUTES) {
            sinceSave = 0;
            save();
        }
    }

    /**
     * Reads the journal's joins and quits: all of them at start (for the record), then what is new since the last
     * read. Keeps the last day and a half for the chart.
     */
    private synchronized void readJournal(boolean all) {
        var journalNow = audit.get();
        if (journalNow == null) {
            return;
        }
        long now = clock.getAsLong();
        try {
            long since = all || events.isEmpty() ? 0 : events.getLast().at() - 5_000;
            java.time.Instant until = null;
            List<PresenceTimeline.Event> found = new java.util.ArrayList<>();
            while (true) {
                var search = de.raindancer.core.moderation.audit.AuditSearch.in("player")
                        .since(java.time.Instant.ofEpochMilli(since))
                        .limit(de.raindancer.core.moderation.audit.AuditSearch.MAX_LIMIT);
                if (until != null) {
                    search = search.until(until);
                }
                List<de.raindancer.core.moderation.audit.AuditEntry> page = journalNow.search(search);
                for (var entry : page) {
                    boolean join = "joined".equals(entry.action());
                    if (entry.actor() != null && (join || "quit".equals(entry.action()))
                            && seen.putIfAbsent(entry.id(), entry.at().toEpochMilli()) == null) {
                        found.add(new PresenceTimeline.Event(entry.at().toEpochMilli(), entry.actor(), join));
                    }
                }
                if (page.size() < de.raindancer.core.moderation.audit.AuditSearch.MAX_LIMIT) {
                    break;
                }
                java.time.Instant oldest = page.getLast().at();
                if (oldest.equals(until)) {
                    break;
                }
                until = oldest;
            }
            if (all) {
                history.record(PresenceTimeline.of(found, now).peak());
            }
            events.addAll(found);
            events.sort(java.util.Comparator.comparingLong(PresenceTimeline.Event::at));
            events.removeIf(event -> event.at() < now - WINDOW);
            seen.values().removeIf(at -> at < now - WINDOW);
            PresenceTimeline rolling = PresenceTimeline.of(events, now);
            history.record(rolling.peak());
            journal = rolling;
        } catch (RuntimeException unreadable) {
            log.warn("The audit journal could not be read for the player charts: {}", unreadable.toString());
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
