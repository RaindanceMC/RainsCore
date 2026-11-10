package de.raindancer.core.ui.camera;

import java.util.Optional;

/**
 * When a {@link Cutscene} sends its camera on, where to, and when a caption shows. The camera is sent one segment
 * ahead with an interpolation of one segment, so the client glides it there exactly as the next one arrives — a
 * continuous flight from a packet every few ticks, rather than a jittery move every tick.
 */
public final class CutsceneTimeline {

    private final CameraPath path;
    private final int segmentTicks;
    private final double holdSeconds;
    private int lastCaption = -1;

    /**
     * @param segmentTicks how often the camera is sent on; the display's teleport interpolation, 1 to 59
     * @param holdSeconds  how long the last shot stays before the cutscene ends
     */
    public CutsceneTimeline(CameraPath path, int segmentTicks, double holdSeconds) {
        if (segmentTicks < 1 || segmentTicks > 59) {
            throw new IllegalArgumentException("a segment is 1 to 59 ticks, not " + segmentTicks);
        }
        this.path = path;
        this.segmentTicks = segmentTicks;
        this.holdSeconds = Math.max(0, holdSeconds);
    }

    public CameraPath path() {
        return path;
    }

    public int segmentTicks() {
        return segmentTicks;
    }

    public boolean sendAt(int tick) {
        return tick % segmentTicks == 0;
    }

    /** Where on the path the camera is sent at {@code tick}: one segment ahead. */
    public double targetSeconds(int tick) {
        return (tick + segmentTicks) / 20.0;
    }

    public boolean over(int tick) {
        return tick / 20.0 >= path.seconds() + holdSeconds;
    }

    /** The key whose caption shows now: once each, and only keys with one. */
    public Optional<Integer> captionAt(int tick) {
        int reached = path.keyReached(tick / 20.0);
        if (reached <= lastCaption) {
            return Optional.empty();
        }
        lastCaption = reached;
        String caption = path.keys().get(reached).caption();
        return caption == null || caption.isBlank() ? Optional.empty() : Optional.of(reached);
    }
}
