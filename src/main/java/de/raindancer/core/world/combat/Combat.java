package de.raindancer.core.world.combat;

import de.raindancer.core.platform.util.PluginCode;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Who may hurt whom on this server.
 *
 * <h2>What this is for</h2>
 * Turning PvP off, turning mobs off, or turning either off in one world and not another — without
 * every plugin that wants it writing its own damage listener. Three plugins each cancelling
 * {@code EntityDamageByEntityEvent} at their own priority is how one of them silently loses, and
 * which one depends on load order.
 *
 * <h2>Two things it deliberately does not do</h2>
 * <ul>
 *   <li><b>It changes nothing until asked.</b> A library that switches PvP off the moment it is
 *       installed has broken somebody's server, and they will not know which plugin did it.</li>
 *   <li><b>It never refuses the world itself.</b> Falling, drowning, lava, a cactus — nobody is
 *       behind those, and a plugin that stops them has turned off the game rather than a rule.</li>
 * </ul>
 *
 * <h2>Where "who attacked" comes from</h2>
 * Not from here. The server names whatever delivered the damage — an arrow, a wolf, a splash of
 * potion, a block of TNT — and a rule written against that is a rule anybody can walk around by
 * shooting instead of hitting. Following the chain back to a person is {@link CombatListener}'s job,
 * and it is the part that is easy to get wrong; this class only decides what to do about the answer.
 *
 * <p>That split is also what makes the rules testable without a server, which matters because the
 * rules have two opposite failure modes: too permissive is an argument between players, too strict is
 * somebody unable to mine with their own TNT.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread, and read from every region thread on every hit. Nothing here allocates or
 * locks: a damage event is one of the most frequent things a server does.
 */
public final class Combat {

    private static final LogChannel log = Log.of("combat");

    /** What the server allows, unless a world says otherwise. */
    private volatile boolean playersMayHurtPlayers = true;
    private volatile boolean playersMayHurtMobs = true;
    private volatile boolean mobsMayHurtPlayers = true;

    /** What one world allows, when it differs. Absent means "whatever the server says". */
    private final Map<String, Rules> byWorld = new ConcurrentHashMap<>();

    /**
     * Anything else that may have the final say — a claim, an arena, a plugin's own rule.
     *
     * <p>Without this, a claims plugin wanting a duelling arena inside a peaceful world has to cancel
     * this plugin's cancellation from a listener at a different priority, and then the two fight over
     * one event for ever.
     */
    private final List<Asked> alsoAsked = new CopyOnWriteArrayList<>();

    private final Companions companions = new Companions();

    /** An extra rule, and the plugin it belongs to — null for one added without saying. */
    private record Asked(String plugin, Function<Attack, Verdict> rule) {
    }

    /**
     * A verdict and whose rule it was.
     *
     * @param decidedBy the plugin whose extra rule answered, or null for Core's own rules and for
     *                  rules added without a plugin — so a listener can word the refusal the way that
     *                  plugin wants (see {@code Messages.overrideFor})
     */
    public record Ruling(Verdict verdict, String decidedBy) {

        static Ruling core(Verdict verdict) {
            return new Ruling(verdict, null);
        }
    }

    /** What one world allows. Null in a field means "not set here". */
    private record Rules(Boolean pvp, Boolean playersOnMobs, Boolean mobsOnPlayers) {

        static final Rules NOTHING_SET = new Rules(null, null, null);

        Rules withPvp(boolean allowed) {
            return new Rules(allowed, playersOnMobs, mobsOnPlayers);
        }

        Rules withPlayersOnMobs(boolean allowed) {
            return new Rules(pvp, allowed, mobsOnPlayers);
        }

        Rules withMobsOnPlayers(boolean allowed) {
            return new Rules(pvp, playersOnMobs, allowed);
        }

        boolean isEmpty() {
            return pvp == null && playersOnMobs == null && mobsOnPlayers == null;
        }
    }

    /** Mobs bound to a player that are not tamed — each counts as its owner, like a tamed wolf. */
    public Companions companions() {
        return companions;
    }

    // ------------------------------------------------------------------------ what is allowed

    /** Whether one player may hurt another, anywhere the world does not say otherwise. */
    public void pvp(boolean allowed) {
        playersMayHurtPlayers = allowed;
    }

    /** The same, in one world only. */
    public void pvp(String world, boolean allowed) {
        change(world, rules -> rules.withPvp(allowed));
    }

    /**
     * Whether players and mobs may hurt each other at all — both directions at once.
     *
     * <p>Both, because switching only one is nearly always a mistake: a server where players cannot
     * kill mobs but mobs still kill players is a worse game rather than a gentler one. The two are
     * separately settable for the case where somebody means it.
     */
    public void pve(boolean allowed) {
        playersMayHurtMobs = allowed;
        mobsMayHurtPlayers = allowed;
    }

    /** The same, in one world only. */
    public void pve(String world, boolean allowed) {
        change(world, rules -> rules.withPlayersOnMobs(allowed).withMobsOnPlayers(allowed));
    }

    /** Whether a player may hurt a mob — for a building server that still wants zombies dangerous. */
    public void playersMayHurtMobs(boolean allowed) {
        playersMayHurtMobs = allowed;
    }

    public void playersMayHurtMobs(String world, boolean allowed) {
        change(world, rules -> rules.withPlayersOnMobs(allowed));
    }

    /** Whether a mob may hurt a player. */
    public void mobsMayHurtPlayers(boolean allowed) {
        mobsMayHurtPlayers = allowed;
    }

    public void mobsMayHurtPlayers(String world, boolean allowed) {
        change(world, rules -> rules.withMobsOnPlayers(allowed));
    }

    /** Forgets a world's own rules, so it follows the server's again. */
    public void clearWorld(String world) {
        if (world != null) {
            byWorld.remove(key(world));
        }
    }

    /** Every world that has rules of its own. */
    public List<String> worldsWithTheirOwnRules() {
        List<String> named = new ArrayList<>(byWorld.keySet());
        named.sort(String::compareTo);
        return named;
    }

    private void change(String world, Function<Rules, Rules> how) {
        if (world == null || world.isBlank()) {
            return;
        }
        byWorld.compute(key(world), (ignored, rules) -> {
            Rules changed = how.apply(rules == null ? Rules.NOTHING_SET : rules);
            // Removed rather than kept as a row of nulls, so worldsWithTheirOwnRules() answers what
            // it says it does.
            return changed.isEmpty() ? null : changed;
        });
    }

    /**
     * World names are matched however they were typed.
     *
     * <p>A name written into a config file will not match the server's capitalisation, and a rule that
     * silently does not apply is worse than one that is refused: nobody looks for a typo in something
     * that appeared to work.
     */
    private static String key(String world) {
        return world.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------------------- asking

    /** Whether one player may hurt another in this world. */
    public boolean isPvpAllowed(String world) {
        Boolean here = rulesFor(world).pvp();
        return here != null ? here : playersMayHurtPlayers;
    }

    /** Whether players and mobs may hurt each other in this world, in either direction. */
    public boolean isPveAllowed(String world) {
        return isPlayerOnMobAllowed(world) && isMobOnPlayerAllowed(world);
    }

    public boolean isPlayerOnMobAllowed(String world) {
        Boolean here = rulesFor(world).playersOnMobs();
        return here != null ? here : playersMayHurtMobs;
    }

    public boolean isMobOnPlayerAllowed(String world) {
        Boolean here = rulesFor(world).mobsOnPlayers();
        return here != null ? here : mobsMayHurtPlayers;
    }

    private Rules rulesFor(String world) {
        if (world == null || world.isBlank()) {
            return Rules.NOTHING_SET;
        }
        Rules here = byWorld.get(key(world));
        return here == null ? Rules.NOTHING_SET : here;
    }

    /**
     * Whether this attack may go ahead.
     *
     * <p>The one call a listener makes. Never throws, and answers {@link Verdict#ALLOWED} for anything
     * it does not understand — an attack nobody could untangle must not become a refusal nobody can
     * explain.
     */
    public Verdict judge(Attack attack) {
        return decide(attack).verdict();
    }

    /** {@link #judge}, and which plugin's rule decided it. */
    public Ruling decide(Attack attack) {
        if (attack == null || !attack.hasSomebodyBehindIt()) {
            // Nobody to hold responsible: the world itself, or a listener that could not work out
            // who did it. Not ours to refuse either way.
            return Ruling.core(Verdict.ALLOWED);
        }
        if (attack.isSelfInflicted()) {
            // Never refused, and never handed to anybody else's rule either: whether somebody may
            // hurt themselves is not a rule about other people, and refusing it makes them immortal
            // to their own explosives.
            return Ruling.core(Verdict.ALLOWED);
        }
        // Asked before anything else is decided, mob-versus-mob included. That was wrong at first:
        // returning ALLOWED for two mobs before asking meant a claims plugin protecting somebody's
        // livestock never got the chance — a zombie could kill the cows inside a claim and the claim
        // would never hear about it.
        Ruling fromSomebodyElse = askTheOthers(attack);
        if (fromSomebodyElse != null) {
            return fromSomebodyElse;
        }
        if (attack.isMobVersusMob()) {
            // Nobody else objected, so: the game playing itself. Refusing it here would break farms,
            // iron golems and a dozen things nobody was asking about.
            return Ruling.core(Verdict.ALLOWED);
        }
        if (attack.isPlayerVersusPlayer() && !isPvpAllowed(attack.world())) {
            return Ruling.core(Verdict.NO_PVP);
        }
        if (attack.isPlayerHurtingMob() && !isPlayerOnMobAllowed(attack.world())) {
            if (attack.throughPet()) {
                // Somebody's wolf fighting a zombie. Traced back to its owner, which is right for
                // PvP — a wolf set on a player is that player's doing — but wrong here: refusing it
                // means a pet cannot defend its owner on a server where players are not meant to
                // hunt. The animal is doing what animals do.
                return Ruling.core(Verdict.ALLOWED);
            }
            return Ruling.core(Verdict.NO_PVE);
        }
        if (attack.isMobHurtingPlayer() && !isMobOnPlayerAllowed(attack.world())) {
            return Ruling.core(Verdict.NO_PVE);
        }
        return Ruling.core(Verdict.ALLOWED);
    }

    /**
     * Adds something that may overrule the rules, in both directions.
     *
     * <p>Asked before the world's rules, so an arena inside a peaceful world works without a second
     * listener fighting this one over the same event. Answer null to have no opinion.
     *
     * <p>Asked in the order they were added, and the first opinion wins. That order is the order plugins
     * enabled in, so two rules that can disagree about the same attack give a load-order answer: a rule
     * should only have an opinion about the attacks that are its business (its arena, its game).
     */
    public void alsoAsk(Function<Attack, Verdict> rule) {
        alsoAsk(null, rule);
    }

    /**
     * The same, for a rule that belongs to {@code plugin}: a refusal it answers is reported as that
     * plugin's in {@link #decide}, so the attacker is told in that plugin's words.
     */
    public void alsoAsk(String plugin, Function<Attack, Verdict> rule) {
        if (rule != null) {
            alsoAsked.add(new Asked(plugin, rule));
        }
    }

    /**
     * Withdraws a rule added with {@link #alsoAsk}, by the same reference. A plugin that unloads must
     * take its rule with it, or the rule keeps answering from a plugin that is gone.
     *
     * @return whether it was there to remove
     */
    public boolean stopAsking(Function<Attack, Verdict> rule) {
        return rule != null && alsoAsked.removeIf(asked -> asked.rule() == rule);
    }

    /**
     * Drops whatever {@code loader}'s code registered here — for a plugin disabled without taking its
     * own back. See {@link PluginCode}.
     *
     * @return how many were dropped
     */
    public int forgetFrom(ClassLoader loader) {
        int before = alsoAsked.size();
        alsoAsked.removeIf(asked -> PluginCode.isFrom(asked.rule(), loader));
        return before - alsoAsked.size();
    }

    /** How many extra rules there are. */
    public int extraRules() {
        return alsoAsked.size();
    }

    private Ruling askTheOthers(Attack attack) {
        for (Asked asked : alsoAsked) {
            try {
                Verdict said = asked.rule().apply(attack);
                if (said != null) {
                    return new Ruling(said, asked.plugin());
                }
            } catch (RuntimeException failure) {
                // Ignored rather than taken as a refusal. A broken exemption must not silently switch
                // PvP off for the whole server, and it must not switch it on either — so it is as
                // though it had no opinion.
                log.error(failure, "An extra combat rule threw and was ignored.");
            }
        }
        return null;
    }
}
