package de.raindancer.core.ui.effect;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.text.Text;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.choose.ParticleChooser;
import de.raindancer.core.ui.choose.SoundChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.prompt.ChatPrompts;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One cue: what it plays, and every way to change it.
 *
 * <h2>Why there are two ways to set a sound and both are needed</h2>
 * A chooser gives you one sound out of everything the server has, browsable and auditioned as you go. That is
 * the right tool for a click, a page turn, a refusal — and it is useless for a cannon, because a cannon is
 * fifteen sounds with volumes, pitches and delays, and no picker will ever express that.
 *
 * <p>So the layers are typed, in the notation {@link SoundSequence} reads — which is not a compromise but the
 * thing server owners were already writing by hand in config files. What is new is that a mistake in it is
 * <em>reported</em>: {@link SoundSequence#problemsIn} names the layer it could not read, so a typo costs that
 * layer and says so, rather than silently producing a quieter cue than the one on the screen.
 *
 * <h2>Why "back to default" exists</h2>
 * Because the alternative to a wrong rebinding is remembering what it was, and nobody does. A cue that can be
 * put back is one somebody will experiment with; a cue that cannot is one they leave alone.
 */
public final class CueMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** How long somebody is given to type a layer line before the question lapses. */
    private static final Duration TYPING_WINDOW = Duration.ofSeconds(120);

    private final Effects effects;
    private final String cue;
    private final Runnable save;
    private final ChatPrompts prompts;

    public CueMenu(Player viewer, Brand brand, Menu parent, Effects effects, String cue, Runnable save) {
        this(viewer, brand, parent, effects, cue, save, null);
    }

    /**
     * @param prompts how a layer line is typed. Without one the two "type the layers" buttons are greyed with
     *                the reason rather than drawn dead — the choosers and every other button still work
     */
    public CueMenu(Player viewer, Brand brand, Menu parent, Effects effects, String cue, Runnable save,
                   ChatPrompts prompts) {
        super(viewer, brand, parent, 3);
        this.effects = effects;
        this.cue = cue;
        this.save = save;
        this.prompts = prompts;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>" + cue);
    }

    @Override
    public String breadcrumb() {
        return "Cue";
    }

    private Effect current() {
        return effects.boundTo(cue).orElse(Effect.silence());
    }

    @Override
    protected void render() {
        Effect now = current();

        set(4, Icons.of(now.isSilent() ? Material.STRUCTURE_VOID : Material.JUKEBOX,
                "<white>" + cue, describe(now)));

        set(10, Icons.of(Material.NOTE_BLOCK, "<yellow>Pick one sound",
                        List.of("<gray>Browse everything this server has.",
                                "<gray>Auditioned as you go.",
                                "<dark_gray>Replaces every sound layer with this one.")),
                click -> new SoundChooser(viewer, brand(), this, "Pick a sound for " + cue,
                        picked -> rebind(SoundSequence.of(new SoundCue(picked, 1f, 1f)), now.bursts()))
                        .open());

        toolbar(1, prompts != null, Icons.of(Material.WRITABLE_BOOK, "<yellow>Type the sound layers",
                        List.of("<gray>For anything bigger than one sound.",
                                "<dark_gray>NAME@volume~pitch>delayMs^repeat, separated by ;",
                                "<dark_gray>Now: " + shortened(now.sounds().written()))),
                "This build has no chat prompt wired.",
                click -> askForLayers(true, now));

        set(12, Icons.of(Material.BLAZE_POWDER, "<aqua>Pick one particle",
                        List.of("<gray>Browse everything this server has.",
                                "<dark_gray>Replaces every particle layer with this one.")),
                click -> new ParticleChooser(viewer, brand(), this, "Pick a particle for " + cue,
                        picked -> rebind(now.sounds(),
                                ParticleSequence.of(ParticleCue.of(picked, 20))))
                        .open());

        toolbar(3, prompts != null, Icons.of(Material.PAPER, "<aqua>Type the particle layers",
                        List.of("<gray>For a layered effect.",
                                "<dark_gray>NAME@count~spread#rrggbb, separated by ;",
                                "<dark_gray>Now: " + shortened(particlesWritten(now)))),
                "This build has no chat prompt wired.",
                click -> askForLayers(false, now));

        set(14, Icons.of(Material.BELL, "<green>Hear it",
                        List.of("<gray>Played where you are standing.")),
                click -> effects.play(viewer.getUniqueId(), cue));

        toolbar(5, !now.isSilent(), Icons.of(Material.STRUCTURE_VOID, "<yellow>Silence it",
                        List.of("<gray>Switches this cue off everywhere.",
                                "<dark_gray>Better than deleting it: a missing cue is a warning in",
                                "<dark_gray>the log every time a plugin asks, and a silent one is a",
                                "<dark_gray>decision.")),
                "It is already silent.",
                click -> {
                    effects.define(cue, Effect.silence());
                    persist();
                    tell("<yellow>" + cue + " is silent.");
                    refresh();
                });

        toolbar(7, true, Icons.of(Material.STRUCTURE_BLOCK, "<green>Back to default",
                        List.of("<gray>Whatever the plugin that owns this cue shipped.",
                                "<dark_gray>The alternative to a wrong change is remembering",
                                "<dark_gray>what it was, and nobody does.")),
                "", click -> {
                    // Undefined rather than set to a guess: the owning plugin defines its cues as it starts,
                    // so the honest way back is to stop overriding and let it. Said out loud, because until
                    // the next restart the cue is genuinely unbound.
                    effects.undefine(cue);
                    persist();
                    tell("<green>" + cue + " will be whatever its plugin defines. "
                            + "<gray>Until the next restart it is unbound.</gray>");
                    refresh();
                });
    }

    /** What this cue plays, in words, for the item at the top of the page. */
    private List<String> describe(Effect effect) {
        List<String> lore = new ArrayList<>();
        if (effect.isSilent()) {
            lore.add("<dark_gray>Silent.");
            return lore;
        }
        if (!effect.sounds().isSilent()) {
            lore.add("<yellow>Sound: <white>" + shortened(effect.sounds().written()));
        }
        if (!effect.bursts().isNothing()) {
            lore.add("<aqua>Particles: <white>" + shortened(particlesWritten(effect)));
        }
        return lore;
    }

    /** The particle half as text — {@link ParticleSequence} has no {@code written()}, so it is built here. */
    private static String particlesWritten(Effect effect) {
        List<String> parts = new ArrayList<>();
        for (ParticleCue burst : effect.bursts().bursts()) {
            parts.add(burst.particle() + "@" + burst.count()
                    + (burst.spreadX() > 0 ? "~" + burst.spreadX() : ""));
        }
        return String.join("; ", parts);
    }

    /** Cut to fit a tooltip. A fifteen-layer cannon does not fit on a line and must not stretch the page. */
    private static String shortened(String text) {
        if (text == null || text.isEmpty()) {
            return "nothing";
        }
        return text.length() <= 48 ? text : text.substring(0, 45) + "…";
    }

    /**
     * Asks for a layer line in chat, and reports what could not be read.
     *
     * <p>The problems are shown one per line before anything is bound, so somebody who mistyped the eleventh
     * sound of a cannon is told which one — rather than getting a quieter cannon and no explanation.
     */
    private void askForLayers(boolean sounds, Effect before) {
        viewer.closeInventory();
        tell("<yellow>Type the " + (sounds ? "sound" : "particle") + " layers for <white>" + cue
                + "</white>. <gray>Say <white>cancel</white> to keep what is there.</gray>");
        tell("<dark_gray>" + (sounds
                ? "ENTITY_GENERIC_EXPLODE~0.5; ENTITY_LIGHTNING_BOLT_THUNDER>1250"
                : "DUST@40~0.5#ff2020; CRIT@30~0.5"));

        boolean asked = prompts.ask(viewer.getUniqueId(), "core-cue-layers", TYPING_WINDOW,
                typed -> {
                    String written = typed == null ? "" : typed.strip();
                    if (written.isEmpty() || written.equalsIgnoreCase("cancel")) {
                        open();
                        return;
                    }
                    List<String> problems = sounds
                            ? SoundSequence.problemsIn(written)
                            : ParticleSequence.problemsIn(written);
                    problems.forEach(problem -> tell("<yellow>⚠ " + Text.literal(problem)));

                    if (sounds) {
                        SoundSequence parsed = SoundSequence.parseAndExpand(written);
                        if (parsed.isSilent()) {
                            viewer.sendMessage(Messages.spoken("cues.not-a-sound", "<red>Nothing in that could be read as a sound, so nothing changed."));
                            open();
                            return;
                        }
                        rebind(parsed, before.bursts());
                    } else {
                        ParticleSequence parsed = ParticleSequence.parse(written);
                        if (parsed.isNothing()) {
                            viewer.sendMessage(Messages.spoken("cues.not-a-particle", "<red>Nothing in that could be read as a particle, so nothing changed."));
                            open();
                            return;
                        }
                        rebind(before.sounds(), parsed);
                    }
                },
                () -> {
                    tell("<gray>Nothing was typed, so the cue was left alone.");
                    open();
                });
        if (!asked) {
            // Somebody else is already waiting on this player's next line; told to type here, it
            // would have gone to public chat.
            viewer.sendMessage(Messages.spoken("cues.busy", "<red>You are already being asked something else. Answer or cancel that first."));
            open();
        }
    }

    /**
     * Binds both halves at once.
     *
     * <p>Both, always, because the callers pass through whichever half they are not changing — a rebinding that
     * touched one half and dropped the other would mean picking a new sound silently deleted the particles that
     * went with it.
     */
    private void rebind(SoundSequence sounds, ParticleSequence bursts) {
        effects.define(cue, new Effect(sounds, bursts));
        persist();
        tell("<green>✔ " + cue + " changed.");
        // Heard immediately: the whole point of doing this on a screen rather than in a file is finding out
        // now whether it sounds right.
        effects.play(viewer.getUniqueId(), cue);
        open();
    }

    private void persist() {
        if (save != null) {
            save.run();
        }
    }

    private void tell(String miniMessage) {
        viewer.sendMessage(MINI.deserialize(miniMessage));
    }
}
