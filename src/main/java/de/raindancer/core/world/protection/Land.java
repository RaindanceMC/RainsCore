package de.raindancer.core.world.protection;

import de.raindancer.core.platform.util.PluginCode;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * "May this player do that here?" — asked by anybody, answered by whoever owns the ground.
 *
 * <h2>What Core owns and what it does not</h2>
 * Core owns the <em>question</em>: the vocabulary ({@link LandAction}, {@link LandFlag},
 * {@link LandAudience}), the server's policy over flags, the enforcement listeners, and the throttled refusal
 * a player sees. It owns none of the data. Which piece of ground is where, who is trusted on it and what it is
 * called all come from a {@link LandProvider} that another plugin registers.
 *
 * <p>That split corrects an earlier design in which Core held a whole claim model — shape, members, bans,
 * pantry, bank, fence, entry fee. Those are what a claim <em>is</em>, and a foundation that knows them cannot
 * host anything that is not a claim.
 *
 * <h2>Why every plugin should ask</h2>
 * Because more than one thing moves players and changes blocks: a warp pointing into somebody's house, a
 * teleport request accepted across a border, a ghast line landing in a stranger's garden, a farm world about
 * to be regenerated with builds in it. Each of those has to ask, and before this each either asked nobody or
 * wrote its own rules.
 *
 * <h2>Not knowing is an answer</h2>
 * With no provider registered every question answers {@link LandVerdict#UNKNOWN} rather than "allowed". The
 * difference decides whether an uninstalled module means "nothing is protected, go ahead and delete the
 * world" or "I cannot tell, so don't". See {@link LandVerdict}.
 */
public final class Land {

    private static final LogChannel log = Log.of("land");

    /**
     * The permission that makes the bypass toggle mean anything.
     *
     * <p>Still {@code rec.bypass} rather than something under {@code rainscore.}: this is what is in every
     * existing server's permission plugin, and renaming it would silently take the bypass away from the admins
     * who have it. {@link #BYPASS_PERMISSION_CORE} is accepted too, for a new server that would rather spell it
     * the way the rest of Core does.
     */
    public static final String BYPASS_PERMISSION = "rec.bypass";

    public static final String BYPASS_PERMISSION_CORE = "rainscore.land.bypass";

    /** Server-wide land administration. Kept under the old name for the same reason as the bypass. */
    public static final String ADMIN_PERMISSION = "rec.admin";

    public static final String ADMIN_PERMISSION_CORE = "rainscore.land.admin";

    /**
     * How long a refused player is left alone before being told again.
     *
     * <p>Somebody holding down the left mouse button at a border produces one refusal per tick. Twenty
     * identical lines a second is not a message, it is a denial of service on their own screen.
     */
    private static final long QUIET_AFTER_REFUSAL_MILLIS = 1_500L;

    private final Messages messages;
    private final LongSupplier clock;
    private final FlagRules flags;
    private final LandFlags landFlags;

    /**
     * At most one. Two plugins answering for the same block is two sets of rules and no tie-breaker.
     *
     * <p>An AtomicReference rather than a volatile field because registering is check-then-act: two plugins
     * enabling at once could both read null and both believe they had the job. Whichever wrote second would
     * win silently, so which plugin's rules a server ran would depend on load order.
     */
    private final AtomicReference<LandProvider> provider =
            new AtomicReference<>();

    private final Set<UUID> bypassing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastRefusal = new ConcurrentHashMap<>();
    /** When each bypass now running was switched on, so a reminder knows how long it has stood. */
    private final Map<UUID, Long> bypassSince = new ConcurrentHashMap<>();
    /** Who has said "stop asking" for their current bypass — cleared the moment it is toggled off. */
    private final Set<UUID> bypassReminderSilenced = ConcurrentHashMap.newKeySet();
    /** Extra yeses on top of an area's no ({@link #alsoAllow}). */
    private final java.util.List<LandRule> alsoAllowed = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** @param clock milliseconds; injected so the refusal throttle can be tested without waiting for it */
    public Land(LandPolicy policy, Messages messages, LongSupplier clock) {
        this.messages = messages;
        this.clock = clock;
        this.flags = new FlagRules(policy);
        this.landFlags = new LandFlags(this, flags);
    }

    // ------------------------------------------------------------------------ who answers

    /**
     * Registers the plugin that knows where the protected ground is.
     *
     * @return false when somebody is already registered, in which case nothing changes — the first one keeps
     *         the job, and the refusal is logged rather than swallowed
     */
    public boolean provider(LandProvider candidate) {
        if (candidate == null) {
            return false;
        }
        if (!provider.compareAndSet(null, candidate)) {
            log.warn("{} offered to answer land questions, but {} already does. Ignoring the second one — "
                            + "two answers for the same block cannot both be enforced.",
                    candidate.name(), provider.get().name());
            return false;
        }
        log.info("Land questions are answered by {}.", candidate.name());
        return true;
    }

    /**
     * Stands the provider down if {@code loader}'s code is it — for a plugin disabled without
     * withdrawing. Left in place, it answers from a plugin that is gone, and refuses the same plugin's
     * fresh copy when it starts again.
     *
     * @return whether it was standing
     */
    public boolean forgetFrom(ClassLoader loader) {
        LandProvider registered = provider.get();
        if (registered == null || !PluginCode.isFrom(registered, loader)) {
            return false;
        }
        withdraw(registered);
        return true;
    }

    /** Stands the provider down — called when the plugin that registered it stops. */
    public void withdraw(LandProvider registered) {
        // Only if it is still the one registered: a module shutting down must not be able to unregister a
        // provider that replaced it in the meantime.
        if (registered != null && provider.compareAndSet(registered, null)) {
            log.info("{} has stopped answering land questions; nothing is protected until something does.",
                    registered.name());
        }
    }

    /** Whether anybody is answering at all. */
    public boolean hasProvider() {
        return provider.get() != null;
    }

    /** Who is answering, for the console line and for the diagnostics command. */
    public Optional<LandProvider> provider() {
        return Optional.ofNullable(provider.get());
    }

    // ------------------------------------------------------------------------ looking ground up

    /** The protected area at this spot, if there is one and if anybody knows. */
    public Optional<ProtectedArea> areaAt(Location location) {
        LandProvider answering = provider.get();
        if (answering == null || location == null) {
            return Optional.empty();
        }
        return answering.at(location);
    }

    /**
     * The area a player is considered to be in.
     *
     * <p>Not always the one under their feet — see {@link LandProvider#around}. Use this rather than
     * {@link #areaAt} whenever the subject is a player, or somebody standing on their own border flickers
     * between two answers several times a second.
     */
    public Optional<ProtectedArea> areaAround(Player player) {
        LandProvider answering = provider.get();
        if (answering == null || player == null) {
            return Optional.empty();
        }
        return answering.around(player);
    }

    /**
     * Whether it is safe to do something sweeping to this whole world.
     *
     * <p>{@code ALLOWED} means somebody checked and there is nothing protected in it. {@code REFUSED} means
     * there is. {@code UNKNOWN} means nobody is answering — and read with {@link LandVerdict#orRefuse()} that
     * stops a farm-world regeneration rather than deleting somebody's house on the strength of a missing
     * plugin.
     */
    public LandVerdict safeToReshape(World world) {
        LandProvider answering = provider.get();
        if (answering == null || world == null) {
            return LandVerdict.UNKNOWN;
        }
        return answering.hasAnyIn(world) ? LandVerdict.REFUSED : LandVerdict.ALLOWED;
    }

    /**
     * Asks whoever keeps the claims to hold this rectangle for the server — see
     * {@link LandProvider#holdForServer}.
     *
     * @return empty when it is held, otherwise why not
     */
    public Optional<String> holdForServer(World world, String name, int x1, int z1, int x2, int z2) {
        if (world == null || name == null || name.isBlank()) {
            return Optional.of("no world or no name to hold it under");
        }
        LandProvider answering = provider.get();
        if (answering == null) {
            return Optional.of("nothing on this server keeps claims");
        }
        return answering.holdForServer(world, name, Math.min(x1, x2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(z1, z2));
    }

    /** Gives back ground held for the server. @return whether anything was held under that name */
    public boolean releaseForServer(String name) {
        LandProvider answering = provider.get();
        return answering != null && name != null && answering.releaseForServer(name);
    }

    // ------------------------------------------------------------------------ the admin bypass

    public boolean isBypassing(Player player) {
        return bypassing.contains(player.getUniqueId())
                && hasAny(player, BYPASS_PERMISSION, BYPASS_PERMISSION_CORE);
    }

    /**
     * Turns the bypass on or off.
     *
     * @return whether it is now on
     */
    public boolean toggleBypass(Player player) {
        UUID id = player.getUniqueId();
        if (bypassing.remove(id)) {
            bypassSince.remove(id);
            bypassReminderSilenced.remove(id);
            return false;
        }
        bypassing.add(id);
        bypassSince.put(id, clock.getAsLong());
        return true;
    }

    /**
     * Whoever has been bypassing for at least this long and has not asked to stop hearing about it.
     *
     * <p>Found by a real report: bypass is meant to last exactly as long as the admin is actually
     * working, and forgetting to switch it off is not rare — it is the normal way somebody's own
     * claim quietly stops protecting them, with nothing anywhere saying why. A one-off reminder a
     * player can extend or silence costs far less than the confusion a silent, indefinite bypass
     * causes the one time somebody forgets it is on.
     *
     * <p>Called on a slow timer by whoever hosts this, which is also who actually sends the reminder —
     * this only says who is due, since asking has nothing to do with the flag question the rest of
     * this class answers.
     */
    public List<UUID> dueForBypassReminder(Duration after) {
        long now = clock.getAsLong();
        long threshold = after.toMillis();
        List<UUID> due = new ArrayList<>();
        for (UUID id : bypassing) {
            if (bypassReminderSilenced.contains(id)) {
                continue;
            }
            Long since = bypassSince.get(id);
            if (since != null && now - since >= threshold) {
                due.add(id);
            }
        }
        return due;
    }

    /**
     * Pushes the reminder clock back to now, whether that means "yes, still working" after being
     * asked or simply having been asked at all — either way the next reminder is a full interval away,
     * not immediately on the next check.
     */
    public void postponeBypassReminder(UUID id) {
        if (id != null && bypassing.contains(id)) {
            bypassSince.put(id, clock.getAsLong());
        }
    }

    /** Stops asking, for as long as this particular bypass session lasts — cleared the next time it is toggled. */
    public void silenceBypassReminder(UUID id) {
        if (id != null) {
            bypassReminderSilenced.add(id);
        }
    }

    /**
     * Whether this area's flags are suspended because somebody who bypasses them is standing in it.
     *
     * <p>For the flag questions that carry no player and cannot. {@code BlockRedstoneEvent} has none, and it is
     * the event that decides whether a circuit runs — so a redstone torch placed by an admin powered nothing,
     * a pressure plate they stepped on did nothing, and judging the click instead covered levers and buttons
     * and nothing else.
     *
     * <p>Presence rather than a time window. Redstone propagates for many ticks, so a window generous enough to
     * cover it is a window in which the flag is off for everybody in the world; presence is bounded, visible,
     * and easy to say out loud — a claim's rules are suspended while an admin is standing in it, and they resume
     * when the admin walks out.
     *
     * <p>The cost, plainly: somebody else in the same claim gets working redstone for as long as the admin is
     * there. That is the price of enforcing a flag whose event has no actor. An admin in a claim can already
     * build and break anything in it, so this is smaller than what they could already do, and they can see that
     * they are the reason.
     *
     * <p>The loop is over the bypassing players, not the online ones. That set is empty on almost every server,
     * which makes the usual answer free — and this is asked on every redstone tick.
     */
    public boolean isSuspendedIn(ProtectedArea area) {
        if (area == null || bypassing.isEmpty()) {
            return false;
        }
        for (UUID id : bypassing) {
            Player watcher = Bukkit.getPlayer(id);
            if (watcher == null || !isBypassing(watcher)) {
                continue;
            }
            if (areaAt(watcher.getLocation())
                    .filter(where -> where.id().equals(area.id()))
                    .isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Forgets a player entirely — called when they leave.
     *
     * <p>Both maps, and both for the same reason: without this they grow by one entry for every player who has
     * ever been on the server, and an admin who logs back in is silently still bypassing everything.
     */
    public void forget(UUID id) {
        bypassing.remove(id);
        lastRefusal.remove(id);
        bypassSince.remove(id);
        bypassReminderSilenced.remove(id);
    }

    public boolean isServerAdmin(Player player) {
        return hasAny(player, ADMIN_PERMISSION, ADMIN_PERMISSION_CORE);
    }

    // ------------------------------------------------------------------------ the decision

    /**
     * Whether this player may do that on this piece of ground.
     *
     * <ol>
     *   <li>Unprotected ground — nothing to enforce, so yes.</li>
     *   <li>A bypassing admin — yes, and deliberately first, so broken ground can be fixed.</li>
     *   <li>Otherwise whatever the area says. Trust lists, bans, delegated rights and everything else resolve
     *       behind {@link ProtectedArea#may}, which is why Core models none of them.</li>
     * </ol>
     */
    public boolean has(ProtectedArea area, Player player, LandAction action) {
        if (area == null) {
            return true;
        }
        if (isBypassing(player)) {
            return true;
        }
        return area.may(player.getUniqueId(), action) || allowedByOthers(player, area, action);
    }

    /**
     * Adds a rule that may let a player do what an area refused (a raid). Only consulted on a refusal; a yes wins.
     * A plugin that stops takes its rule back with {@link #stopAllowing}.
     */
    public void alsoAllow(LandRule rule) {
        if (rule != null) {
            alsoAllowed.add(rule);
        }
    }

    /** @return whether it was there to remove */
    public boolean stopAllowing(LandRule rule) {
        return rule != null && alsoAllowed.remove(rule);
    }

    /**
     * Whether one of the extra rules lets this player do it here — without the area's own answer. For things that
     * are refused regardless of membership (a claim's explosion flag) and that only such a rule may open.
     */
    public boolean allowedByOthers(Player player, ProtectedArea area, LandAction action) {
        if (player == null || area == null || alsoAllowed.isEmpty()) {
            return false;
        }
        for (LandRule rule : alsoAllowed) {
            try {
                if (rule.allows(player, area, action)) {
                    return true;
                }
            } catch (RuntimeException broken) {
                // a broken rule is a no; the event goes on
            }
        }
        return false;
    }

    /** The three-way answer, for a caller that needs to tell "no" from "nobody knows". */
    public LandVerdict verdict(Player player, Location location, LandAction action) {
        if (provider.get() == null) {
            return LandVerdict.UNKNOWN;
        }
        Optional<ProtectedArea> area = areaAt(location);
        if (area.isEmpty()) {
            return LandVerdict.ALLOWED;
        }
        return has(area.get(), player, action) ? LandVerdict.ALLOWED : LandVerdict.REFUSED;
    }

    /** The plain yes/no, treating unprotected and unknown ground alike. */
    public boolean can(Player player, Location location, LandAction action) {
        Optional<ProtectedArea> area = areaAt(location);
        return area.isEmpty() || has(area.get(), player, action);
    }

    /**
     * What a listener calls: allowed, or refused with the explanation already sent.
     *
     * @return true when the action may go ahead
     */
    public boolean allow(Player player, Location location, LandAction action) {
        Optional<ProtectedArea> area = areaAt(location);
        if (area.isEmpty()) {
            return true;
        }
        if (has(area.get(), player, action)) {
            return true;
        }
        deny(player, area.get(), action);
        return false;
    }

    /** The same without the message, for the events that arrive many times a tick. */
    public boolean allowSilently(Player player, Location location, LandAction action) {
        Optional<ProtectedArea> area = areaAt(location);
        return area.isEmpty() || has(area.get(), player, action);
    }

    /** Tells a player why not, at most once every {@value #QUIET_AFTER_REFUSAL_MILLIS} milliseconds. */
    public void deny(Player player, ProtectedArea area, LandAction action) {
        long now = clock.getAsLong();
        Long last = lastRefusal.get(player.getUniqueId());
        if (last != null && now - last < QUIET_AFTER_REFUSAL_MILLIS) {
            return;
        }
        lastRefusal.put(player.getUniqueId(), now);
        player.sendActionBar(messages.prefixed("land.denied",
                "claim", area.name(),
                "permission", messages.raw(action.nameKey())));
    }

    // ------------------------------------------------------------------------ flags

    /** The flag resolver, for the screens that show a flag rather than enforce it. */
    /**
     * Whether a teleport here would let this player in, as the protection sees it: nobody's land, a
     * bypass, or an area that admits them, lets teleporting be a way in for their tier and — where walking
     * in is enforced — lets them walk in too. Asked before offering somewhere, so a list does not show a
     * door they would only be turned away at. The arrival itself is still checked when it happens.
     */
    public boolean mayTeleportInto(Player player, Location to) {
        if (player == null || to == null) {
            return false;
        }
        if (isBypassing(player)) {
            return true;
        }
        Optional<ProtectedArea> area = areaAt(to);
        if (area.isEmpty()) {
            return true;
        }
        ProtectedArea there = area.get();
        if (!there.mayEnter(player.getUniqueId())) {
            return false;
        }
        for (LandFlag way : new LandFlag[]{LandFlag.TELEPORT_IN, LandFlag.WALK_IN}) {
            if (landFlags().isEnforced(way) && !flags().isAllowedFor(there, way, player)) {
                return false;
            }
        }
        return true;
    }

    public FlagRules flags() {
        return flags;
    }

    /** Flags resolved by location, which is what the enforcement listeners use. */
    public LandFlags landFlags() {
        return landFlags;
    }

    private static boolean hasAny(Player player, String... nodes) {
        for (String node : nodes) {
            if (player.hasPermission(node)) {
                return true;
            }
        }
        return false;
    }
}
