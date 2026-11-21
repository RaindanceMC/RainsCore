package de.raindancer.core.world.build;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a block in the world is the block a plan asks for, compared as block state strings.
 *
 * <p>Strict about what a builder chooses — the block, which way it faces, its axis, upper or lower half, a slab's
 * position, a door's hinge, a sign's rotation — and blind to what the game works out by itself: a stair's corner
 * shape, a fence's connections, whether a door is open, leaves' distance, redstone power, water inside. An exact
 * comparison would call a correctly built house wrong the moment a fence touched a wall.
 *
 * <p>No server needed: strings in, answer out.
 */
public final class StateMatch {

    /** The properties a player decides by how they place a block. Everything else is the game's. */
    private static final Set<String> CHOSEN = Set.of(
            "facing", "axis", "half", "type", "rotation", "hinge", "part", "face", "attachment",
            "orientation", "hanging", "vertical_direction", "thickness", "tilt", "mode", "instrument");

    private static final Set<String> AIRS = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

    private StateMatch() {
    }

    public static boolean matches(String planned, String actual) {
        String plannedId = id(planned);
        String actualId = id(actual);
        if (isAir(plannedId) || isAir(actualId)) {
            return isAir(plannedId) && isAir(actualId);
        }
        if (!plannedId.equals(actualId)) {
            return false;
        }
        Map<String, String> want = properties(planned);
        Map<String, String> have = properties(actual);
        for (Map.Entry<String, String> wanted : want.entrySet()) {
            if (CHOSEN.contains(wanted.getKey())
                    && have.containsKey(wanted.getKey())
                    && !have.get(wanted.getKey()).equals(wanted.getValue())) {
                return false;
            }
        }
        return true;
    }

    public static boolean isAir(String state) {
        return AIRS.contains(id(state));
    }

    /** {@code minecraft:oak_stairs} from {@code oak_stairs[facing=north]}. */
    public static String id(String state) {
        int bracket = state.indexOf('[');
        String id = bracket < 0 ? state : state.substring(0, bracket);
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    public static Optional<String> property(String state, String name) {
        return Optional.ofNullable(properties(state).get(name));
    }

    static Map<String, String> properties(String state) {
        int open = state.indexOf('[');
        int close = state.lastIndexOf(']');
        Map<String, String> properties = new LinkedHashMap<>();
        if (open < 0 || close <= open) {
            return properties;
        }
        for (String pair : state.substring(open + 1, close).split(",")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                properties.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
            }
        }
        return properties;
    }
}
