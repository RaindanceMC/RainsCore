package de.raindancer.core.ui.hologram;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Boards floating in the world — a leaderboard over a hub, the rules over a lobby. Where they stand is kept in a
 * file of one line per board; each is a text display marked as the plugin's, found again at its spot after a restart
 * (or put back if it is gone) and given fresh text every so often. The text is made off the server thread, so it
 * may read a database.
 *
 * <p>Moved here from the arena (its {@code BoardService}) when Capture the Flag wanted the same boards.
 */
public final class FloatingBoards {

    /** Where one board stands. A world name cannot hold a space — the file is split on them. */
    public record Spot(String world, double x, double y, double z) {
    }

    private static final double FIND = 1.5;

    private final Plugin plugin;
    private final Server server;
    private final YamlStore file;
    private final NamespacedKey mark;
    private final Supplier<Component> text;
    private final int seconds;
    private final List<Location> boards = new CopyOnWriteArrayList<>();
    private volatile Component current = Component.empty();
    private ScheduledTask timer;

    /**
     * @param markKey which displays are these boards' — keep it once boards have been placed with it
     * @param text    the text of every board, asked off the server thread
     * @param seconds how often it is asked
     */
    public FloatingBoards(Plugin plugin, Server server, Path file, String markKey, Supplier<Component> text,
                          int seconds) {
        this.plugin = plugin;
        this.server = server;
        this.file = new YamlStore(file);
        this.mark = new NamespacedKey(plugin, markKey);
        this.text = text;
        this.seconds = Math.max(1, seconds);
    }

    public static String line(Spot spot) {
        return String.format(Locale.ROOT, "%s %.2f %.2f %.2f", spot.world(), spot.x(), spot.y(), spot.z());
    }

    public static Optional<Spot> parse(String line) {
        if (line == null) {
            return Optional.empty();
        }
        String[] parts = line.trim().split(" ");
        if (parts.length != 4 || parts[0].isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Spot(parts[0], Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3])));
        } catch (NumberFormatException garbled) {
            return Optional.empty();
        }
    }

    public void start() {
        for (String line : file.read().getStringList("boards")) {
            parse(line).ifPresent(spot -> {
                World world = server.getWorld(spot.world());
                if (world != null) {
                    boards.add(new Location(world, spot.x(), spot.y(), spot.z()));
                }
            });
        }
        timer = Scheduling.asyncTimer(plugin, 5, seconds, task -> refresh());
    }

    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /** A board where somebody stands, a little above their head. */
    public void place(Location at) {
        Location spot = at.clone().add(0, 2.2, 0);
        spot.setYaw(0);
        spot.setPitch(0);
        boards.add(spot);
        save();
        Scheduling.async(plugin, this::refresh);
    }

    /** The board nearest to somebody, within five blocks, is taken away. */
    public boolean removeNearest(Player admin) {
        Optional<Location> nearest = boards.stream()
                .filter(spot -> spot.getWorld().equals(admin.getWorld()) && spot.distance(admin.getLocation()) <= 5)
                .min(Comparator.comparingDouble(spot -> spot.distance(admin.getLocation())));
        nearest.ifPresent(spot -> {
            boards.remove(spot);
            save();
            Scheduling.region(plugin, spot, () -> marked(spot).forEach(TextDisplay::remove));
        });
        return nearest.isPresent();
    }

    public int count() {
        return boards.size();
    }

    /** Asks for fresh text now rather than at the next turn of the timer. */
    public void refreshSoon() {
        Scheduling.async(plugin, this::refresh);
    }

    private void refresh() {
        current = text.get();
        for (Location spot : boards) {
            Scheduling.region(plugin, spot, () -> show(spot));
        }
    }

    private void show(Location spot) {
        if (!spot.isChunkLoaded()) {
            return;
        }
        List<TextDisplay> found = marked(spot);
        if (found.isEmpty()) {
            found = List.of(spot.getWorld().spawn(spot, TextDisplay.class, display -> {
                display.getPersistentDataContainer().set(mark, PersistentDataType.BYTE, (byte) 1);
                display.setBillboard(Display.Billboard.VERTICAL);
                display.setAlignment(TextDisplay.TextAlignment.CENTER);
                display.setBackgroundColor(Color.fromARGB(150, 10, 6, 20));
                display.setShadowed(true);
                display.setLineWidth(260);
                display.setPersistent(true);
            }));
        }
        Component now = current;
        found.forEach(display -> display.text(now));
    }

    private List<TextDisplay> marked(Location spot) {
        List<TextDisplay> out = new ArrayList<>();
        for (TextDisplay display : spot.getNearbyEntitiesByType(TextDisplay.class, FIND)) {
            if (display.getPersistentDataContainer().has(mark, PersistentDataType.BYTE)) {
                out.add(display);
            }
        }
        return out;
    }

    private void save() {
        List<String> lines = boards.stream()
                .map(spot -> line(new Spot(spot.getWorld().getName(), spot.getX(), spot.getY(), spot.getZ())))
                .toList();
        file.write(yaml -> yaml.set("boards", lines));
    }
}
