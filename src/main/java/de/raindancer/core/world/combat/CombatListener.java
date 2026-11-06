package de.raindancer.core.world.combat;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Vex;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * Working out who actually attacked, and cancelling the ones that are not allowed.
 *
 * <h2>Why this class is the hard part</h2>
 * {@link Combat} decides what is allowed and is ten lines of arithmetic. This decides <em>who did
 * it</em>, and that is where every PvP plugin has ever gone wrong, because the server does not say.
 * The event names whatever object delivered the damage:
 *
 * <ul>
 *   <li>an <b>arrow</b>, a trident, a snowball, an ender pearl — the shooter is behind it, and a rule
 *       that only checks the damager lets anybody shoot their way past PvP being off;</li>
 *   <li>a <b>wolf</b> or a tamed cat — the owner set it on somebody, and treating it as a mob turns
 *       PvP into "bring a dog";</li>
 *   <li>a block of <b>primed TNT</b> — whoever lit it, which may be somebody who has since left;</li>
 *   <li>a <b>lingering potion's cloud</b>, which is not the thrower and not a projectile either;</li>
 *   <li>a <b>fishing rod</b>, a firework, a wind charge, a llama's spit, a wither skull — all
 *       {@code Projectile}, so all one case;</li>
 *   <li>a <b>bolt of lightning</b> from a channelling trident, which is not a projectile and not
 *       alive: without following it, a trident is the cleanest way round a PvP rule in the game;</li>
 *   <li>an evoker's <b>fangs</b> or its <b>vexes</b>, which are summoned rather than thrown.</li>
 * </ul>
 *
 * <p>Each of those has to be followed back to whoever is responsible, and the chain can be more than
 * one link long: a wolf shot by an arrow fired by a player.
 *
 * <p>The list is not from memory. Every type in Paper 26.2 that can name somebody behind it —
 * {@code getShooter}, {@code getSource}, {@code getOwner}, {@code getCausingEntity} — was read off the
 * API, and all of them are handled: {@code Projectile}, {@code AreaEffectCloud}, {@code TNTPrimed},
 * {@code Tameable}, {@code EvokerFangs}, {@code Vex}, {@code LightningStrike}. The one deliberately
 * left out is {@code Item#getThrower}, which is a dropped item and does not do damage.
 *
 * <h2>Two more things it gets right on purpose</h2>
 * <ul>
 *   <li><b>Splash and lingering potions are their own event.</b> A splash of harming hits everybody in
 *       range at once and never produces a damage event naming the thrower, so a plugin listening only
 *       to damage lets a potion do what a sword may not.</li>
 *   <li><b>The refusal is told to the attacker, once.</b> A cancelled event with no message is a
 *       player hitting somebody harder and concluding the server is broken; a message per swing is
 *       chat somebody has to leave to escape. So: throttled, and only ever to the person who did it.</li>
 * </ul>
 *
 * <h2>Priority, in two passes</h2>
 * Decided at {@code LOW}, explained at {@code MONITOR}. Low so a plugin with a more specific opinion —
 * an arena inside a peaceful world — can still see the event and un-cancel it; monitor because that is
 * the only point at which "was this refused" has an answer that will not change. Doing both at once was
 * the first version, and it told people PvP was off while the hit landed.
 *
 * <p>Cancelled events are deliberately <em>not</em> ignored. Something else may have refused first — a
 * claim at {@code LOWEST} — and a player refused in silence concludes the server is broken, so they are
 * told {@code PROTECTED}: something is protecting that, and this does not claim to know whose rule it
 * was.
 */
public final class CombatListener implements Listener {

    private static final LogChannel log = Log.of("combat");

    /** How long before the same player is told again why they cannot. */
    private static final long QUIET_MILLIS = 3_000;

    /** How far back a chain of blame is followed before giving up. */
    private static final int MAX_LINKS = 8;

    private final Combat combat;
    private final LongSupplier clock;
    private final Messages messages;

    /** When each attacker was last told, so a held-down attack is one message and not forty. */
    private final ConcurrentHashMap<UUID, AtomicLong> lastTold = new ConcurrentHashMap<>();

    /**
     * What was decided at {@code LOW}, waiting for {@code MONITOR} to confirm it stuck.
     *
     * <p>Keyed by the event object, which is safe because the two handlers are the same dispatch of the
     * same event: whatever is put in at {@code LOW} is taken out at {@code MONITOR}, on the same thread,
     * a few microseconds later. Weak keys so that an event which somehow never reaches the monitor pass
     * — a listener between the two that throws — is collected rather than kept for ever.
     */
    private final Map<EntityDamageByEntityEvent, Pending> pending =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** One decision, waiting to be explained. */
    private record Pending(Attack attack, Verdict verdict, String decidedBy) {
    }

    /**
     * Where a message to the attacker is sent from.
     *
     * <p>A seam, because the damage event fires in the victim's region and the attacker may be in
     * another — so the message cannot simply be sent from here. Runs inline by default, which is what
     * a test wants; the plugin replaces it with one that schedules against the player.
     */
    private volatile BiConsumer<Player, Runnable> tellOn =
            (player, task) -> task.run();

    public CombatListener(Combat combat, LongSupplier clock, Messages messages) {
        this.combat = combat;
        this.clock = clock;
        this.messages = messages;
    }

    /** Tells this where a message to a player should be sent from. */
    public void tellOn(BiConsumer<Player, Runnable> tellOn) {
        if (tellOn != null) {
            this.tellOn = tellOn;
        }
    }

    // ---------------------------------------------------------------------------- the events

    /**
     * Decides, at {@code LOW}, and says nothing yet.
     *
     * <p>Low so that a plugin with a more specific opinion — an arena inside a peaceful world — can
     * still see the event afterwards and un-cancel it. Which is exactly why the message does not go out
     * here: telling somebody "PvP is off" and then letting the hit land is worse than saying nothing.
     * The verdict is remembered and {@link #onDamageSettled} sends it once the event has stopped
     * changing hands.
     *
     * <p>Not {@code ignoreCancelled}, either. Something else may already have refused this — a claim at
     * {@code LOWEST} — and a player who is refused in silence concludes the server is broken. Judging a
     * cancelled event costs nothing and gives them a reason.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onDamage(EntityDamageByEntityEvent event) {
        Attack attack = read(event.getDamager(), event.getEntity());
        Combat.Ruling ruling = combat.decide(attack);
        Verdict verdict = ruling.verdict();
        if (!verdict.allowed()) {
            event.setCancelled(true);
        }
        if (!verdict.allowed() || event.isCancelled()) {
            // Remembered for the monitor pass. Something else's refusal is worth explaining too, and
            // PROTECTED is the honest word for it: this does not know whose rule it was.
            pending.put(event, verdict.allowed()
                    ? new Pending(attack, Verdict.PROTECTED, null)
                    : new Pending(attack, verdict, ruling.decidedBy()));
        }
    }

    /**
     * Says why, at {@code MONITOR}, once nothing can change its mind.
     *
     * <p>{@code MONITOR} is the only priority at which "was this refused" has a stable answer. A
     * message sent at {@code LOW} is a message sent before the arena plugin has had its say — and then
     * the player is told they cannot while watching the hit land, which is the worst of both.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamageSettled(EntityDamageByEntityEvent event) {
        Pending remembered = pending.remove(event);
        if (remembered != null && event.isCancelled()) {
            tell(remembered.attack(), remembered.verdict(), remembered.decidedBy());
        }
    }

    /**
     * A splash potion, which hits everybody in range without a damage event naming the thrower.
     *
     * <p>Each victim is considered separately, because the answer differs per victim: a potion thrown
     * into a crowd may legitimately hit the thrower's own team-mate and not somebody in a claim. A
     * victim who may not be hit has their share of the effect set to zero rather than the whole potion
     * being cancelled — cancelling it would let one protected player standing nearby save everybody.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        Responsible thrower = whoIsBehind(event.getPotion().getShooter(), 0);
        if (thrower.kind() == Attack.Fighter.NOBODY) {
            return;
        }
        if (!doesHarm(event.getPotion())) {
            // A splash of healing, or speed, or regeneration. Not an attack, and refusing it stops
            // somebody helping their own side — which is a rule nobody asked for and cannot be
            // explained to the player it happens to.
            return;
        }
        Verdict worst = Verdict.ALLOWED;
        String worstBy = null;
        // Copied, because setIntensity may change what getAffectedEntities answers and iterating a
        // collection while changing it is the sort of failure that only happens with a crowd.
        for (LivingEntity hit : List.copyOf(event.getAffectedEntities())) {
            Attack attack = between(thrower.kind(), thrower.id(),
                    event.getPotion().getLocation(), hit);
            Combat.Ruling ruling = combat.decide(attack);
            if (!ruling.verdict().allowed()) {
                event.setIntensity(hit, 0);
                worst = ruling.verdict();
                worstBy = ruling.decidedBy();
            }
        }
        if (!worst.allowed()) {
            tell(between(thrower.kind(), thrower.id(), event.getPotion().getLocation(),
                    event.getEntity()), worst, worstBy);
        }
    }

    /**
     * Whether a potion does harm at all.
     *
     * <p>Read off the effects rather than a list of names: {@code PotionEffectType} says whether it is
     * bad for you, and a list would need editing every time a version adds one.
     */
    private static boolean doesHarm(ThrownPotion potion) {
        for (PotionEffect effect : potion.getEffects()) {
            if (effect.getType().getEffectCategory()
                    == PotionEffectType.Category.HARMFUL) {
                return true;
            }
        }
        return false;
    }

    /** Forgets a player's throttle when they leave. */
    public void forget(UUID who) {
        if (who != null) {
            lastTold.remove(who);
        }
    }

    // ------------------------------------------------------------------- who really did it

    /** Turns "this object damaged that one" into "this person attacked that one". */
    private Attack read(Entity damager, Entity victim) {
        Responsible attacker = whoIsBehind(damager, 0);
        Responsible defender = whoIsBehind(victim, 0);
        Location from = damager == null ? null : damager.getLocation();
        Location where = victim == null ? null : victim.getLocation();
        String world = where != null ? where.getWorld().getName()
                : from != null ? from.getWorld().getName() : "";
        return new Attack(attacker.kind(), attacker.id(), defender.kind(), defender.id(),
                world, attacker.throughPet(), at(where), at(from), meansOf(damager));
    }

    /** How the damager delivered it — read off the thing that hit, before the chain is followed. */
    static Attack.Means meansOf(Entity damager) {
        if (damager instanceof Player player) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            return hand == null || hand.isEmpty() ? Attack.Means.BARE_HANDED : Attack.Means.HELD_ITEM;
        }
        if (damager instanceof Projectile) {
            return Attack.Means.RANGED;
        }
        return damager == null ? Attack.Means.UNKNOWN : Attack.Means.OTHER;
    }

    /**
     * Who is behind something, and of what kind.
     *
     * <p>Both together, which was the correction. Answering only "which player, if any" left every
     * other case to be guessed from the object that delivered the damage — and an arrow is not alive,
     * so a skeleton's arrow came out as {@code NOBODY} and slipped past every rule. The kind has to
     * come from whoever is <em>responsible</em>, not from what they used.
     *
     * @param kind who they are as far as the rules care
     * @param id   which one, when there is one to name
     */
    private record Responsible(Attack.Fighter kind, UUID id, boolean throughPet) {

        static final Responsible NOBODY = new Responsible(Attack.Fighter.NOBODY, null, false);

        static Responsible player(UUID who) {
            return new Responsible(Attack.Fighter.PLAYER, who, false);
        }

        static Responsible mob(UUID which) {
            return new Responsible(Attack.Fighter.MOB, which, false);
        }
    }

    private Attack between(Attack.Fighter attacker, UUID attackerId, Location from, Entity victim) {
        Responsible defender = whoIsBehind(victim, 0);
        Location where = victim == null ? null : victim.getLocation();
        String world = where != null ? where.getWorld().getName()
                : from != null ? from.getWorld().getName() : "";
        // Only a splash potion comes through here: thrown, so ranged.
        return new Attack(attacker, attackerId, defender.kind(), defender.id(), world, false,
                at(where), at(from), Attack.Means.RANGED);
    }

    private static Attack.At at(Location location) {
        return location == null ? null
                : new Attack.At(location.getX(), location.getY(), location.getZ());
    }

    /**
     * Follows a chain of blame back to whoever is responsible.
     *
     * <p>Recursive, because the chain really can be several links long: a wolf hurt by an arrow fired
     * by a player. Bounded, because a chain that loops — which a badly-behaved plugin can construct by
     * setting an entity as its own shooter — would otherwise be an endless loop on the thread ticking
     * the world.
     *
     * <p>A pet resolves to its <b>owner</b> on both sides, which is the point of tracing at all: a
     * wolf set on somebody is its owner attacking, and somebody's wolf being killed is that person
     * being attacked. Without the second half, "PvP off" means "kill their dog instead".
     */
    private Responsible whoIsBehind(Object thing, int depth) {
        if (thing == null) {
            return Responsible.NOBODY;
        }
        if (depth > MAX_LINKS) {
            log.warn("Gave up following an attack back after {} links; something is pointing at "
                    + "itself.", MAX_LINKS);
            return Responsible.NOBODY;
        }
        if (thing instanceof Player player) {
            return Responsible.player(player.getUniqueId());
        }
        if (thing instanceof OfflinePlayer offline) {
            // What Tameable.getOwner() answers for an owner who is not online. AnimalTamer is the
            // declared type and OfflinePlayer is what it is in practice; a tamer that is neither
            // falls through to the mob case below rather than being lost.
            return Responsible.player(offline.getUniqueId());
        }
        if (thing instanceof Projectile projectile) {
            // An arrow, a trident, a snowball, a firework, a llama's spit. Whoever shot it is
            // responsible — and when that is a skeleton, the answer is MOB rather than nobody.
            return orElse(whoIsBehind(projectile.getShooter(), depth + 1), thing);
        }
        if (thing instanceof AreaEffectCloud cloud) {
            // What a lingering potion leaves behind. Not a projectile, and its source is the thrown
            // potion rather than the thrower — so this is the link a damage-only listener misses.
            return orElse(whoIsBehind(cloud.getSource(), depth + 1), thing);
        }
        if (thing instanceof TNTPrimed tnt) {
            // Whoever lit it, which Paper remembers. They may have logged out since, which is fine:
            // a UUID is still who did it.
            return orElse(whoIsBehind(tnt.getSource(), depth + 1), thing);
        }
        if (thing instanceof LightningStrike bolt) {
            // A channelling trident in the rain, or a lightning rod somebody aimed. Not a projectile
            // and not alive, so without this it is weather — and weather is nobody's doing, which
            // makes a trident the cleanest way round a PvP rule in the game.
            Responsible person = whoIsBehind(bolt.getCausingPlayer(), depth + 1);
            return person.kind() != Attack.Fighter.NOBODY ? person
                    : whoIsBehind(bolt.getCausingEntity(), depth + 1);
        }
        if (thing instanceof EvokerFangs fangs) {
            // Summoned, and the summoner is who did it. An evoker's, normally — but a plugin can
            // give a player one, and then it is a player attacking.
            return orElse(whoIsBehind(fangs.getOwner(), depth + 1), thing);
        }
        if (thing instanceof Vex vex) {
            // Summoned by an evoker. A mob either way in vanilla, but a plugin can summon one, and
            // then the vex is that player attacking.
            return orElse(whoIsBehind(vex.getOwner(), depth + 1), thing);
        }
        if (thing instanceof Tameable pet && pet.isTamed()) {
            // A wolf set on somebody is its owner attacking, and somebody's wolf being hurt is that
            // person being hurt. The oldest way round a PvP rule there is.
            Responsible owner = whoIsBehind(pet.getOwner(), depth + 1);
            return owner.kind() == Attack.Fighter.PLAYER
                    ? new Responsible(owner.kind(), owner.id(), true)
                    : orElse(owner, thing);
        }
        if (thing instanceof Entity bound && combat.companions().isCompanion(bound.getUniqueId())) {
            // A pet a plugin made that the server does not know as tamed — the same as a wolf.
            UUID owner = combat.companions().ownerOf(bound.getUniqueId()).orElse(null);
            if (owner != null) {
                return new Responsible(Attack.Fighter.PLAYER, owner, true);
            }
        }
        if (thing instanceof Entity entity) {
            return kindOf(entity) == Attack.Fighter.MOB
                    ? Responsible.mob(entity.getUniqueId()) : Responsible.NOBODY;
        }
        return Responsible.NOBODY;
    }

    /**
     * The answer from further up the chain, or the thing itself when nobody up there was named.
     *
     * <p>An arrow with no shooter is still an arrow: it may have come from a dispenser, or from a
     * plugin. That is nobody's doing, and nobody's doing is allowed.
     */
    private static Responsible orElse(Responsible found, Object thing) {
        if (found.kind() != Attack.Fighter.NOBODY) {
            return found;
        }
        return thing instanceof Entity entity && kindOf(entity) == Attack.Fighter.MOB
                ? Responsible.mob(entity.getUniqueId()) : Responsible.NOBODY;
    }

    /**
     * What something is, as far as the rules care.
     *
     * <p>Anything alive that is not a person is a mob — including somebody's horse, which is
     * deliberate: a rule about hurting animals should cover the animal somebody is fond of.
     */
    private static Attack.Fighter kindOf(Entity entity) {
        if (entity instanceof Player) {
            return Attack.Fighter.PLAYER;
        }
        return entity instanceof LivingEntity ? Attack.Fighter.MOB : Attack.Fighter.NOBODY;
    }

    // ----------------------------------------------------------------------------- telling

    /**
     * Tells the attacker why, at most once every few seconds.
     *
     * <p>Only the attacker: the person being protected did not do anything and does not need to be
     * told about it. And throttled, because a cancelled attack with no message is a player who hits
     * harder and then reports the server as broken, while a message per swing is chat somebody has to
     * log out to escape.
     */
    private void tell(Attack attack, Verdict verdict) {
        tell(attack, verdict, null);
    }

    /** @param decidedBy whose rule refused it, so the attacker is told in that plugin's words */
    private void tell(Attack attack, Verdict verdict, String decidedBy) {
        if (verdict.reasonKey() == null || messages == null) {
            return;
        }
        UUID who = attack.attackerId();
        if (who == null || attack.attacker() != Attack.Fighter.PLAYER) {
            // A mob being stopped has nobody to tell.
            return;
        }
        long now = clock.getAsLong();
        AtomicLong last = lastTold.computeIfAbsent(who, ignored -> new AtomicLong(0L));
        long previous = last.get();
        if (now - previous < QUIET_MILLIS || !last.compareAndSet(previous, now)) {
            // Either recently told, or another thread got there first — on Folia two hits on two
            // victims can be judged at the same instant. Either way, one message.
            return;
        }
        Player attacker = Bukkit.getPlayer(who);
        if (attacker == null) {
            return;
        }
        Component said = messages.prefixedFor(decidedBy, verdict.reasonKey());
        // On the attacker's own thread, not this one. The damage event fires in the *victim's* region,
        // and on Folia the attacker may be standing in another — shooting across a region boundary is
        // ordinary. Touching them from here would be an IllegalStateException inside a damage event,
        // which takes the tick with it.
        tellOn.accept(attacker, () -> attacker.sendMessage(said));
    }
}
