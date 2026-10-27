package de.raindancer.core.world.protection;

import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeping the people inside a private area out of sight.
 *
 * <p>The {@link LandFlag#VISIBLE_FROM_OUTSIDE} flag. A walled garden is not private if everybody outside can
 * watch you in it, and no permission covers that — it is not about what anybody may <em>do</em>.
 *
 * <h2>Hidden, not merely omitted</h2>
 * {@code Player.hide} rather than suppressing a packet or leaving somebody off a list: hiding a player is a
 * promise a dozen subsystems have to keep, and every place it usually leaks is a place that did half of it. This
 * way the client is genuinely not told they are there.
 *
 * <h2>Why it is recomputed rather than tracked</h2>
 * Both ends move. A watcher walking past a private garden and a resident walking into one are the same change
 * seen from opposite sides, so the pairing is worked out from where everybody is at the moment it is asked
 * rather than remembered — remembering it is how somebody ends up permanently invisible after a disconnect.
 *
 * <p>Everything here is undone by {@link #reveal}, which the plugin calls on shutdown. A player left hidden by a
 * reload is invisible until they reconnect, and nothing on their screen would explain why.
 */
public final class Seclusion {

    private final Plugin plugin;
    private final Land land;

    /** watcher → the people currently hidden from them, so only real changes cost a packet. */
    private final Map<UUID, Set<UUID>> hiddenFrom = new ConcurrentHashMap<>();

    public Seclusion(Plugin plugin, Land land) {
        this.plugin = plugin;
        this.land = land;
    }

    /**
     * Works out who each player may see, and hides or shows the difference.
     *
     * <p>Called on a timer rather than on every move: two players walking towards each other generate a move
     * event each per tick, and the answer changes only when one of them crosses a border.
     */
    public void refresh() {
        if (!land.landFlags().isEnforced(LandFlag.VISIBLE_FROM_OUTSIDE) || !land.hasProvider()) {
            revealEverybody();
            return;
        }
        for (Player watcher : plugin.getServer().getOnlinePlayers()) {
            Set<UUID> shouldHide = new HashSet<>();
            for (Player subject : plugin.getServer().getOnlinePlayers()) {
                if (subject.equals(watcher)) {
                    continue;
                }
                if (!mayBeSeen(subject, watcher)) {
                    shouldHide.add(subject.getUniqueId());
                }
            }
            apply(watcher, shouldHide);
        }
    }

    /**
     * Whether this watcher may see this person.
     *
     * <p>The tier is the <em>watcher's</em> standing on the ground the subject is on, which is what makes
     * "people I trust may see in, strangers may not" one setting rather than a list. A watcher standing on the
     * same ground always sees them: the flag is about being seen from outside.
     */
    private boolean mayBeSeen(Player subject, Player watcher) {
        var where = land.areaAt(subject.getLocation());
        if (where.isEmpty()) {
            return true;   // not on protected ground at all
        }
        ProtectedArea area = where.get();
        var watcherStands = land.areaAt(watcher.getLocation());
        if (watcherStands.isPresent() && watcherStands.get().id().equals(area.id())) {
            return true;   // both inside; this flag is about being seen from outside
        }
        if (land.isBypassing(watcher)) {
            return true;
        }
        return land.flags().isAllowedFor(area, LandFlag.VISIBLE_FROM_OUTSIDE, watcher);
    }

    /** Hides and shows only what changed, so a steady state costs nothing. */
    private void apply(Player watcher, Set<UUID> shouldHide) {
        Set<UUID> currently = hiddenFrom.computeIfAbsent(watcher.getUniqueId(),
                key -> ConcurrentHashMap.newKeySet());

        for (UUID id : new HashSet<>(currently)) {
            if (!shouldHide.contains(id)) {
                Player subject = plugin.getServer().getPlayer(id);
                if (subject != null) {
                    // On the watcher's own thread: on Folia, showing a player from another region is an
                    // IllegalStateException that takes the timer with it.
                    Scheduling.entity(plugin, watcher, () -> watcher.showPlayer(plugin, subject));
                }
                currently.remove(id);
            }
        }
        for (UUID id : shouldHide) {
            if (currently.add(id)) {
                Player subject = plugin.getServer().getPlayer(id);
                if (subject != null) {
                    Scheduling.entity(plugin, watcher, () -> watcher.hidePlayer(plugin, subject));
                }
            }
        }
    }

    /** Shows everybody to everybody again. Called on shutdown, and when the flag stops being enforced. */
    public void revealEverybody() {
        hiddenFrom.forEach((watcherId, hidden) -> {
            Player watcher = plugin.getServer().getPlayer(watcherId);
            if (watcher == null) {
                return;
            }
            for (UUID id : hidden) {
                Player subject = plugin.getServer().getPlayer(id);
                if (subject == null) {
                    continue;
                }
                // Runs in place during onDisable — see Scheduling — so nobody stays hidden.
                Scheduling.entity(plugin, watcher, () -> watcher.showPlayer(plugin, subject));
            }
        });
        hiddenFrom.clear();
    }

    /** Whether {@code subject}'s body is hidden from {@code watcher} here — they stay on watcher's tab list. */
    public boolean hides(UUID watcher, UUID subject) {
        Set<UUID> hidden = hiddenFrom.get(watcher);
        return hidden != null && hidden.contains(subject);
    }

    /**
     * Forgets a player, both as a watcher and as somebody hidden.
     *
     * <p>Both halves: without the second, somebody who logs out while hidden stays in everybody's set for ever
     * and is hidden again the moment they return, for no reason anybody can see.
     */
    public void forget(UUID player) {
        hiddenFrom.remove(player);
        hiddenFrom.values().forEach(hidden -> hidden.remove(player));
    }

    /** How many pairings are being held — for the diagnostic that answers "is this leaking". */
    public int hiddenPairings() {
        return hiddenFrom.values().stream().mapToInt(Set::size).sum();
    }
}
