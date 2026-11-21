package de.raindancer.core.content.music;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Who plays music to a player when several things want to at once: the music on logging on, the AFK camera, the
 * radio they tuned in, a cutscene. Only one is heard: the highest {@link Layer}, and of two on the same layer the
 * later one. A part that is outranked is told to {@link Part#hush}; when what outranked it is over, the highest
 * one left is told to {@link Part#resume}. A part with no reason to wait (a greeting a minute late is no
 * greeting) leaves when hushed.
 *
 * <p>Static because it is shared through Core by plugins that never see each other. The parts are told on the
 * thread that entered or left, after the stage's own lock is let go, so a part may enter or leave from inside.
 * Since 2.115.0.
 */
public final class MusicStage {

    /** In rank order, lowest first. */
    public enum Layer {
        /** The music on logging on. */
        GREETING,
        /** While the player is away: the AFK camera. */
        AWAY,
        /** What the player chose themselves: the radio. Above everything ambient. */
        CHOSEN,
        /** Something that happens once: an intro, a first visit. The radio waits for it. */
        MOMENT
    }

    /** What plays. Told when to fall silent and when it may go on. */
    public interface Part {
        void hush();

        void resume();
    }

    /** One lock for every player: music starts and stops a few times a minute, never in a hot path. */
    private static final Object LOCK = new Object();
    private static final Map<UUID, List<Cue>> STAGE = new HashMap<>();
    private static final AtomicLong ORDER = new AtomicLong();
    private static final Comparator<Cue> RANK = Comparator.<Cue, Layer>comparing(cue -> cue.layer)
            .thenComparingLong(cue -> cue.order);

    private MusicStage() {
    }

    /**
     * Comes on for {@code player}. Outranked from the start, it is {@linkplain Cue#live() not live} and is told
     * nothing until it may {@linkplain Part#resume resume}; it should not start its sound until then.
     */
    public static Cue enter(UUID player, Layer layer, Part part) {
        Cue cue = new Cue(player, layer, part, ORDER.incrementAndGet());
        Cue before;
        Cue after;
        synchronized (LOCK) {
            List<Cue> cues = STAGE.computeIfAbsent(player, id -> new ArrayList<>());
            before = top(cues);
            cues.add(cue);
            cues.sort(RANK);
            after = top(cues);
        }
        if (before != null && after == cue) {
            before.part.hush();
        }
        return cue;
    }

    /** The layer heard now, if any. */
    public static Optional<Layer> playing(UUID player) {
        synchronized (LOCK) {
            Cue top = top(STAGE.getOrDefault(player, List.of()));
            return top == null ? Optional.empty() : Optional.of(top.layer);
        }
    }

    /** Whether something above {@code layer} is on for {@code player} — asked before even getting ready to play. */
    public static boolean outranked(UUID player, Layer layer) {
        return playing(player).map(top -> top.compareTo(layer) > 0).orElse(false);
    }

    /** The player left: everything of theirs is dropped, and nobody is told. */
    public static void forget(UUID player) {
        synchronized (LOCK) {
            STAGE.remove(player);
        }
    }

    private static Cue top(List<Cue> cues) {
        return cues.isEmpty() ? null : cues.getLast();
    }

    /** One part's place on a player's stage. */
    public static final class Cue {

        private final UUID player;
        private final Layer layer;
        private final Part part;
        private final long order;

        private Cue(UUID player, Layer layer, Part part, long order) {
            this.player = player;
            this.layer = layer;
            this.part = part;
            this.order = order;
        }

        public Layer layer() {
            return layer;
        }

        /** Whether this is what the player hears now. */
        public boolean live() {
            synchronized (LOCK) {
                return top(STAGE.getOrDefault(player, List.of())) == this;
            }
        }

        /** Off the stage; what is highest after it resumes, if this was what played. Twice is harmless. */
        public void leave() {
            Cue next;
            synchronized (LOCK) {
                List<Cue> cues = STAGE.get(player);
                if (cues == null) {
                    return;
                }
                boolean wasTop = top(cues) == this;
                if (!cues.remove(this)) {
                    return;
                }
                if (cues.isEmpty()) {
                    STAGE.remove(player);
                }
                next = wasTop ? top(cues) : null;
            }
            if (next != null) {
                next.part.resume();
            }
        }
    }
}
