package de.raindancer.core.content.music;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One of an owner's music tracks: the plugin's namespace in the resource pack, its name there, how long it plays,
 * and its title as the owner wrote it (the file name, e.g. "Beyond The Clouds - Matthew Pablo").
 * The client plays it as the sound event {@code <namespace>:music.<name>}.
 */
public record Track(String namespace, String name, double seconds, String title) {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");

    public Track {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("not a resource-pack namespace: " + namespace);
        }
        if (title == null || title.isBlank()) {
            title = name;
        }
    }

    /** Kept for plugins built before 2.115.0: titled by its name. */
    public Track(String namespace, String name, double seconds) {
        this(namespace, name, seconds, name);
    }

    /** The sound event the client plays. */
    public String sound() {
        return namespace + ":music." + name;
    }

    /** A file name as a resource-pack name: lower case, a–z, 0–9 and underscores only. */
    public static String slug(String fileName) {
        String base = fileName.replaceFirst("(?i)\\.ogg$", "").toLowerCase(Locale.ROOT);
        return base.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}
