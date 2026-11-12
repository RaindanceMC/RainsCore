package de.raindancer.core.moderation.feed;

import java.util.List;
import java.util.Map;

/**
 * What one staff member did during one stay in admin mode, as far as this server saw it.
 *
 * @param commands        every command typed, in order, up to {@link AdminSessionRecorder#MAX_LINES}
 * @param commandsOmitted commands beyond that, only counted
 * @param actions         what the audit log says they did (a ban, a fine, money given, an inventory edited …)
 * @param placed          blocks placed, by material
 * @param broken          blocks broken, by material
 * @param teleports       how often they were teleported
 * @param worlds          the worlds they were teleported into, other than the one they started in
 * @param gameModes       their own game mode switches, as {@code "SURVIVAL → CREATIVE"}
 */
public record AdminSessionRecap(long startedAt, long endedAt,
                                List<Line> commands, int commandsOmitted,
                                List<Line> actions, int actionsOmitted,
                                Map<String, Integer> placed, Map<String, Integer> broken,
                                int teleports, List<String> worlds, List<String> gameModes) {

    public record Line(long at, String text) {
    }

    public AdminSessionRecap {
        commands = List.copyOf(commands);
        actions = List.copyOf(actions);
        placed = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(placed));
        broken = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(broken));
        worlds = List.copyOf(worlds);
        gameModes = List.copyOf(gameModes);
    }

    /** Nothing run and nothing changed. */
    public boolean isEmpty() {
        return commands.isEmpty() && commandsOmitted == 0 && actions.isEmpty() && actionsOmitted == 0
                && placed.isEmpty() && broken.isEmpty() && teleports == 0 && gameModes.isEmpty();
    }
}
