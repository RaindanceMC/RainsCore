package de.raindancer.core.ui.identity;

import net.kyori.adventure.text.Component;

import java.util.Optional;

/** When a styled nametag floats over somebody, and what it says. No server in here. */
public final class NametagRule {

    /**
     * @param inForeignTeam in a scoreboard team some other plugin made — their vanilla name stays as that
     *                      plugin wants it, and a second name above it would be worse than either
     */
    public boolean shows(boolean dead, boolean spectating, boolean invisible, boolean vanished,
                         boolean inForeignTeam) {
        return !dead && !spectating && !invisible && !vanished && !inForeignTeam;
    }

    /** The name, and the subtitle under it when there is one. */
    public Component text(Component name, Optional<Component> subtitle) {
        return subtitle.map(line -> name.append(Component.newline()).append(line)).orElse(name);
    }

    /**
     * Whether one viewer is shown a wearer's name.
     *
     * @param ownName      the viewer is the wearer — vanilla never shows you your own, so only on their switch
     * @param wantsOwn     the wearer switched on seeing their own name
     * @param seesWearer   the server lets the viewer see the wearer at all
     */
    public boolean seenBy(boolean ownName, boolean wantsOwn, boolean seesWearer) {
        return ownName ? wantsOwn : seesWearer;
    }
}
