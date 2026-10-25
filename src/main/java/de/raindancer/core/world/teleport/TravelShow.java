package de.raindancer.core.world.teleport;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effect;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.effect.ParticleCue;
import de.raindancer.core.ui.effect.ParticleSequence;
import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.core.ui.effect.SoundCue;
import de.raindancer.core.ui.effect.SoundSequence;

import java.util.Optional;
import java.util.UUID;

/**
 * The sound and the sparkle of every teleport on the server, whichever plugin sent it.
 *
 * <p>One per server, held by Core, because every plugin builds its own {@link Travel} — homes, warps
 * and teleport requests each have one — and a player's chosen arrival sound has to follow them through
 * all of them. {@link Travel} asks this at departure, every few ticks of the warm-up and on arrival.
 *
 * <p>The server's half is three cues an owner can rebind like any other ({@link Cues#TELEPORT_DEPART},
 * {@link Cues#TELEPORT_WAIT}, {@link Cues#TELEPORT}); the player's half is whatever {@link TravelLooks}
 * one plugin registers.
 */
public final class TravelShow {

    private static final LogChannel log = Log.of("travel");

    /** Particles per draw while somebody waits, and in the burst where they land — until the owner says. */
    public static final int DEFAULT_WAIT_DENSITY = 4;
    public static final int DEFAULT_ARRIVAL_DENSITY = 80;

    private volatile int waitDensity = DEFAULT_WAIT_DENSITY;
    private volatile int arrivalDensity = DEFAULT_ARRIVAL_DENSITY;

    private final Effects effects;
    private final java.util.function.Predicate<UUID> hidden;
    private volatile TravelLooks looks = TravelLooks.SERVERS;
    private volatile boolean complained;

    public TravelShow(Effects effects) {
        this(effects, who -> false);
    }

    /**
     * @param hidden who is vanished. Their teleports are silent and bare: a sound where they stood or
     *               landed, or particles around an empty spot, tells everybody nearby they are there
     */
    public TravelShow(Effects effects, java.util.function.Predicate<UUID> hidden) {
        this.effects = effects;
        this.hidden = hidden == null ? who -> false : hidden;
    }

    /**
     * How dense the waiting particles and the arrival burst are, from Core's settings. The arrival's
     * count replaces whatever its cue says; nought is none. Waiting is at least one, or there is nothing.
     */
    public void densities(int waiting, int arriving) {
        waitDensity = Math.clamp(waiting, 1, 20);
        arrivalDensity = Math.clamp(arriving, 0, 300);
    }

    public int waitDensity() {
        return waitDensity;
    }

    /** Where travellers' own choices come from. One source at a time; the last to register wins. */
    public void looks(TravelLooks source) {
        looks = source == null ? TravelLooks.SERVERS : source;
    }

    /** Lets go of {@code source} if it is still the one in use — for a plugin that is disabling. */
    public void release(TravelLooks source) {
        if (looks == source) {
            looks = TravelLooks.SERVERS;
        }
    }

    public void departed(UUID traveller, String world, double x, double y, double z) {
        if (hidden.test(traveller)) {
            return;
        }
        TravelLook look = lookFor(traveller);
        Effect server = effects.boundTo(Cues.TELEPORT_DEPART).orElse(Effect.silence());
        effects.playAt(world, x, y, z, look.depart() == null ? server : withSound(server, look.depart()));
    }

    public void arrived(UUID traveller, String world, double x, double y, double z) {
        if (hidden.test(traveller)) {
            return;
        }
        TravelLook look = lookFor(traveller);
        Effect server = withDensity(effects.boundTo(Cues.TELEPORT).orElse(Effect.silence()), arrivalDensity);
        effects.playAt(world, x, y, z, look.arrive() == null ? server : withSound(server, look.arrive()));
    }

    /**
     * One second of the countdown, for the traveller alone — so it is heard even while vanished, where
     * the departure and arrival are not: nobody else is told anything.
     */
    public void counting(UUID traveller, int secondsLeft) {
        TravelLook look = lookFor(traveller);
        effects.play(traveller, look.tick() == null
                ? effects.boundTo(Cues.TELEPORT_TICK).orElse(Effect.silence())
                : Effect.of(look.tick()));
    }

    /** The particle drawn while they wait, or empty for none. */
    public Optional<String> waitParticle(UUID traveller) {
        if (!effects.isEnabled() || hidden.test(traveller)) {
            return Optional.empty();
        }
        String chosen = lookFor(traveller).waitParticle();
        if (chosen != null) {
            return chosen.isBlank() ? Optional.empty() : Optional.of(chosen.trim());
        }
        return effects.boundTo(Cues.TELEPORT_WAIT)
                .map(Effect::particles)
                .filter(burst -> burst != null && !burst.isNothing())
                .map(ParticleCue::particle);
    }

    /**
     * Whether the spot the traveller will land on shimmers in their waiting particles while they wait, so
     * whoever stands there sees somebody is coming. Not for a landing on another server, nor for anybody whose
     * waiting particles are none — vanished travellers included.
     */
    public boolean marksLanding(UUID traveller, boolean otherServer) {
        return !otherServer && waitParticle(traveller).isPresent();
    }

    /** The traveller's colour for the waiting particle, or empty for white. */
    public Optional<Integer> waitColour(UUID traveller) {
        return Optional.ofNullable(lookFor(traveller).waitColour());
    }

    /** The other end of the traveller's gradient, or empty for one colour. */
    public Optional<Integer> waitColourTo(UUID traveller) {
        return Optional.ofNullable(lookFor(traveller).waitColourTo());
    }

    /** Particles per draw while this traveller waits: their own, held to the server's bounds, or the server's. */
    public int waitDensity(UUID traveller) {
        Integer chosen = lookFor(traveller).waitDensity();
        return chosen == null ? waitDensity : Math.clamp(chosen, 1, 20);
    }

    public ParticleShape waitShape(UUID traveller) {
        ParticleShape chosen = lookFor(traveller).waitShape();
        return chosen == null ? ParticleShape.SPIRAL : chosen;
    }

    /** The same bursts, each with {@code count} particles; none at all for nought. */
    private static Effect withDensity(Effect effect, int count) {
        if (count <= 0) {
            return Effect.of(effect.sounds(), ParticleSequence.nothing());
        }
        return Effect.of(effect.sounds(), new ParticleSequence(effect.bursts().bursts().stream()
                .map(burst -> new ParticleCue(burst.particle(), count, burst.spreadX(), burst.spreadY(),
                        burst.spreadZ(), burst.speed(), burst.colour()))
                .toList()));
    }

    /** The server's effect with the traveller's sound in place of its own; its particles stay. */
    private static Effect withSound(Effect server, SoundCue sound) {
        return Effect.of(SoundSequence.of(sound), server == null ? ParticleSequence.nothing() : server.bursts());
    }

    private TravelLook lookFor(UUID traveller) {
        try {
            TravelLook look = looks.lookFor(traveller);
            return look == null ? TravelLook.SERVERS : look;
        } catch (RuntimeException broken) {
            // Somebody else's plugin. A teleport goes ahead with the server's effects rather than not at all.
            if (!complained) {
                complained = true;
                log.warn("The plugin choosing teleport effects failed ({}); the server's are used.", broken.toString());
            }
            return TravelLook.SERVERS;
        }
    }
}
