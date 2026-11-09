package de.raindancer.core.moderation.feed;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * What moderation did on this server, told to whoever wants to pass it on (a Discord feed, a log shipper): a
 * punishment given or lifted, an anti-cheat alert, a report filed. Only what happened <em>here</em> — what another
 * server did is that server's to tell.
 *
 * <p>Listeners run on the thread that made the event happen, so they must be quick and never throw; one that does
 * is logged and skipped, never reaches the caller.
 */
public final class ModerationFeed {

    private static final LogChannel log = Log.of("moderation");

    /** Stops listening when closed. */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private final List<Consumer<PunishmentNotice>> punishments = new CopyOnWriteArrayList<>();
    private final List<Consumer<AnticheatAlert>> alerts = new CopyOnWriteArrayList<>();
    private final List<Consumer<ReportNotice>> reports = new CopyOnWriteArrayList<>();

    private final List<Consumer<AdminModeNotice>> adminModes = new CopyOnWriteArrayList<>();

    public Subscription onAdminMode(Consumer<AdminModeNotice> listener) {
        return add(adminModes, listener);
    }

    public void adminMode(AdminModeNotice notice) {
        tell(adminModes, notice);
    }

    public Subscription onPunishment(Consumer<PunishmentNotice> listener) {
        return add(punishments, listener);
    }

    public Subscription onAnticheat(Consumer<AnticheatAlert> listener) {
        return add(alerts, listener);
    }

    public Subscription onReport(Consumer<ReportNotice> listener) {
        return add(reports, listener);
    }

    public void punished(Punishment given) {
        tell(punishments, new PunishmentNotice(given, false));
    }

    public void lifted(Punishment lifted) {
        tell(punishments, new PunishmentNotice(lifted, true));
    }

    public void anticheat(AnticheatAlert alert) {
        tell(alerts, alert);
    }

    public void reported(ReportNotice report) {
        tell(reports, report);
    }

    private static <T> Subscription add(List<Consumer<T>> listeners, Consumer<T> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private static <T> void tell(List<Consumer<T>> listeners, T what) {
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(what);
            } catch (RuntimeException failed) {
                log.warn("A moderation feed listener failed: {}", failed.toString());
            }
        }
    }
}
