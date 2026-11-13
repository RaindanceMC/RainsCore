package de.raindancer.core.platform.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How much one client may send in a second. The channel is open to anything that can speak the game's
 * protocol, not only to the mod.
 */
final class FrameBudget {

    enum Verdict {
        PASS,
        DROP,
        /** Dropped, and this player has now been over for as many seconds in a row as it takes to be told about. */
        DROP_AND_REPORT
    }

    private static final class Window {
        long start;
        int frames;
        int bytes;
        boolean over;
        int streak;
    }

    private final int framesPerSecond;
    private final int bytesPerSecond;
    private final int secondsBeforeReport;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    FrameBudget(int framesPerSecond, int bytesPerSecond, int secondsBeforeReport) {
        this.framesPerSecond = framesPerSecond;
        this.bytesPerSecond = bytesPerSecond;
        this.secondsBeforeReport = secondsBeforeReport;
    }

    Verdict take(UUID player, int bytes, long now) {
        Verdict[] verdict = {Verdict.PASS};
        windows.compute(player, (id, window) -> {
            if (window == null) {
                window = new Window();
                window.start = now;
            }
            long age = now - window.start;
            if (age >= 1000 || age < 0) {
                if (!window.over || age >= 2000 || age < 0) {
                    window.streak = 0;
                }
                window.start = now;
                window.frames = 0;
                window.bytes = 0;
                window.over = false;
            }
            if (window.frames + 1 > framesPerSecond || window.bytes + bytes > bytesPerSecond) {
                verdict[0] = Verdict.DROP;
                if (!window.over) {
                    window.over = true;
                    if (++window.streak == secondsBeforeReport) {
                        verdict[0] = Verdict.DROP_AND_REPORT;
                    }
                }
            } else {
                window.frames++;
                window.bytes += bytes;
            }
            return window;
        });
        return verdict[0];
    }

    void forget(UUID player) {
        windows.remove(player);
    }
}
