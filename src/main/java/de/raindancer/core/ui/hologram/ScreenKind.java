package de.raindancer.core.ui.hologram;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Something a screen can show — registered by whichever plugin knows it ({@link Screens#register}), put up by staff
 * with {@code /screen place <id>}. A personal kind shows every viewer their own text: each player near the screen
 * gets a display only they can see.
 *
 * @param id    what staff type and what is written down where it stands: lowercase letters, digits and dashes
 * @param icon  for the list of kinds
 * @param text  the text, asked off the server thread every few seconds — may read a database; the viewer is null for
 *              a kind that is the same for everybody
 */
public record ScreenKind(String id, String title, String description, Material icon, boolean personal,
                         Function<Viewer, Component> text) {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    /** Who a personal screen is being written for. */
    public record Viewer(UUID id, String name) {
    }

    public ScreenKind {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("A screen kind's id is lowercase letters, digits and dashes: " + id);
        }
        Objects.requireNonNull(text, "text");
        title = title == null ? id : title;
        description = description == null ? "" : description;
        icon = icon == null ? Material.OAK_SIGN : icon;
    }

    public static ScreenKind everyone(String id, String title, String description, Material icon,
                                      Supplier<Component> text) {
        return new ScreenKind(id, title, description, icon, false, viewer -> text.get());
    }

    public static ScreenKind personal(String id, String title, String description, Material icon,
                                      Function<Viewer, Component> text) {
        return new ScreenKind(id, title, description, icon, true, text);
    }
}
