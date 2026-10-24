package de.raindancer.core.ui.help;

import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds the guides plugins ship: every {@code rains-help/*.yml} inside an enabled plugin's jar.
 *
 * <p>Read from the jar rather than declared through an API, so a module adds a topic by adding a file — nothing
 * to register, no Core version to wait for, and a bundle carrying nine modules ships nine modules' guides
 * without knowing it. A module switched off inside a bundle still has its files here; its topics say
 * {@code module:} and are hidden while it is not running.
 */
public final class HelpSources {

    /** Where in a jar the guide lives. */
    public static final String FOLDER = "rains-help/";

    /** One file, and which plugin shipped it. */
    public record Shipped(String plugin, String name, String yaml) {
    }

    private HelpSources() {
    }

    /** Every guide file of these plugins. A jar that cannot be read is a line in {@code problems}. */
    public static List<Shipped> read(List<Plugin> plugins, List<String> problems) {
        List<Shipped> found = new ArrayList<>();
        for (Plugin plugin : plugins) {
            Path jar;
            try {
                var source = plugin.getClass().getProtectionDomain().getCodeSource();
                if (source == null) {
                    continue;
                }
                jar = Path.of(source.getLocation().toURI());
            } catch (URISyntaxException | SecurityException | IllegalArgumentException unknown) {
                continue;
            }
            if (!Files.isRegularFile(jar)) {
                continue;
            }
            try {
                found.addAll(fromJar(plugin.getName(), jar));
            } catch (IOException unreadable) {
                problems.add(plugin.getName() + ": its jar could not be read for help files — " + unreadable.getMessage());
            }
        }
        return found;
    }

    /** The guide files in one jar, in name order. */
    static List<Shipped> fromJar(String plugin, Path jar) throws IOException {
        List<Shipped> found = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(FOLDER) || !name.endsWith(".yml")
                        || name.indexOf('/', FOLDER.length()) >= 0) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    found.add(new Shipped(plugin, name, new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                }
            }
        }
        found.sort(java.util.Comparator.comparing(Shipped::name));
        return found;
    }
}
