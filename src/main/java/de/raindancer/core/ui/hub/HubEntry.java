package de.raindancer.core.ui.hub;

import org.bukkit.Material;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One door in {@code /hub}: a module (or plugin) as a player meets it.
 *
 * @param id         stable key; re-declaring it replaces the door
 * @param commands   the root command names it brings, which is how its page finds their descriptions
 * @param permission who sees the door at all, or null for everybody
 */
public record HubEntry(String id, String name, String version, String description, Material icon,
                       String accent, String permission, List<String> commands) {

    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}");

    public HubEntry {
        id = required(id, "id");
        name = required(name, "name");
        version = version == null ? "" : version.trim();
        description = description == null ? "" : description.strip();
        icon = icon == null ? Material.PAPER : icon;
        if (accent == null || !HEX.matcher(accent).matches()) {
            throw new IllegalArgumentException("the accent of '" + id + "' must look like #RRGGBB, not " + accent);
        }
        accent = accent.toUpperCase(java.util.Locale.ROOT);
        permission = permission == null || permission.isBlank() ? null : permission.trim();
        commands = commands == null ? List.of() : List.copyOf(commands);
    }

    /** A door with the look {@link HubLook} has for that id. */
    public static HubEntry of(String id, String name, String version, String description,
                              List<String> commands) {
        HubLook look = HubLook.of(id);
        return new HubEntry(id, name, version, description, look.icon(), look.accent(), null, commands);
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a hub entry needs a " + what);
        }
        return value.trim();
    }
}
