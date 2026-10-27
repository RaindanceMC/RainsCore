package de.raindancer.core.ui.tablist;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * Which tab-list removals to swallow: Bukkit's {@code hidePlayer} also takes the player off the tab list, which is
 * wrong when only their body is meant to be out of sight (a private claim, the anti-ESP shield). {@link TabKeeper}
 * drops those removals; this decides which, and remembers what it kept so it can be undone — Paper sends no
 * removal for a hidden player who quits, and none for a second hide on top of the first (vanish).
 */
public final class TabKeep {

    /** A viewer still listing a target whose body they cannot see. */
    public record Listing(UUID viewer, UUID target) {
    }

    private final List<BiPredicate<UUID, UUID>> reasons = new CopyOnWriteArrayList<>();
    private volatile Supplier<Set<UUID>> vanished = Set::of;
    private final Map<UUID, Set<UUID>> kept = new ConcurrentHashMap<>();

    /** While {@code (viewer, target)} holds, a removal of target from viewer's tab list is dropped. */
    public void keepListedWhile(BiPredicate<UUID, UUID> bodyOnlyHidden) {
        reasons.add(bodyOnlyHidden);
    }

    /** Never kept, whatever else hides them. */
    public void vanished(Supplier<Set<UUID>> source) {
        this.vanished = source == null ? Set::of : source;
    }

    boolean keeps(UUID viewer, UUID target) {
        try {
            if (vanished.get().contains(target)) {
                return false;
            }
            for (BiPredicate<UUID, UUID> reason : reasons) {
                if (reason.test(viewer, target)) {
                    return true;
                }
            }
        } catch (RuntimeException broken) {
            return false;
        }
        return false;
    }

    /** Whether a removal of {@code targets} from viewer's tab list is dropped; only when every one is kept. */
    public boolean drop(UUID viewer, List<UUID> targets) {
        if (targets.isEmpty()) {
            return false;
        }
        for (UUID target : targets) {
            if (!keeps(viewer, target)) {
                return false;
            }
        }
        kept.computeIfAbsent(viewer, key -> ConcurrentHashMap.newKeySet()).addAll(targets);
        return true;
    }

    /**
     * Kept entries whose reason has ended, forgotten as they are returned. The caller removes those the viewer
     * still cannot see; one shown again was re-added by the server itself.
     */
    public List<Listing> stale() {
        List<Listing> ended = new ArrayList<>();
        kept.forEach((viewer, targets) -> targets.removeIf(target -> {
            if (keeps(viewer, target)) {
                return false;
            }
            ended.add(new Listing(viewer, target));
            return true;
        }));
        kept.values().removeIf(Set::isEmpty);
        return ended;
    }

    /** Somebody left: the viewers still listing them, who need telling; and as a viewer they are forgotten. */
    public List<UUID> quit(UUID player) {
        kept.remove(player);
        List<UUID> listing = new ArrayList<>();
        kept.forEach((viewer, targets) -> {
            if (targets.remove(player)) {
                listing.add(viewer);
            }
        });
        kept.values().removeIf(Set::isEmpty);
        return listing;
    }
}
