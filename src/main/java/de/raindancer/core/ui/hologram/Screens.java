package de.raindancer.core.ui.hologram;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Screens — floating text put up in the world by staff, showing whatever a plugin registered as a {@link ScreenKind}:
 * the online players, the richest, a guild's members, or, for a personal kind, every viewer their own stats.
 *
 * <p>Where they stand is kept in {@code screens.yml}, one line each. The displays themselves are never saved with the
 * world: they are put back whenever their ground is loaded, so a removed screen or a plugin gone leaves nothing
 * behind. A personal screen gives each player within {@value #RANGE} blocks a display only that player can see.
 * Texts are made off the server thread; everything touching the world happens on the region that owns it.
 */
public final class Screens implements Listener {

    private static final LogChannel log = Log.of("screens");
    /** How near a player has to be to get their own display of a personal screen. */
    public static final double RANGE = 24;
    /** How near staff have to stand to mean a screen without giving its number. */
    public static final double REACH = 6;
    private static final int SECONDS = 2;
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private record Registered(Plugin owner, ScreenKind kind) {
    }

    /** What stands in the world for one screen right now. Only touched on the screen's region. */
    private static final class Live {
        TextDisplay shared;
        final Map<UUID, TextDisplay> personal = new HashMap<>();
    }

    private final Plugin plugin;
    private final Server server;
    private final YamlStore file;
    private final ScreenRule rule = new ScreenRule();
    private final Map<String, Registered> kinds = new ConcurrentHashMap<>();
    private final List<PlacedScreen> placed = new CopyOnWriteArrayList<>();
    private final Map<Integer, Live> live = new ConcurrentHashMap<>();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    private ScheduledTask timer;

    public Screens(Plugin plugin, Server server, YamlStore file) {
        this.plugin = plugin;
        this.server = server;
        this.file = file;
    }

    /**
     * Makes a kind of screen available. A kind with the same id replaces the earlier one. Taken back by itself when
     * {@code owner} is disabled.
     */
    public void register(Plugin owner, ScreenKind kind) {
        Registered before = kinds.put(kind.id(), new Registered(owner, kind));
        if (before != null && !before.owner().equals(owner)) {
            log.warn("Screen kind '{}' of {} replaced by {}'s.", kind.id(), before.owner().getName(), owner.getName());
        }
    }

    public void unregister(String id) {
        kinds.remove(id);
    }

    /** Every kind that can be put up, by title. */
    public List<ScreenKind> kinds() {
        return kinds.values().stream().map(Registered::kind)
                .sorted(Comparator.comparing(ScreenKind::title, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public Optional<ScreenKind> kind(String id) {
        return Optional.ofNullable(id == null ? null : kinds.get(id.toLowerCase(java.util.Locale.ROOT)))
                .map(Registered::kind);
    }

    /** Which plugin a kind is from, for the list. */
    public Optional<String> ownerOf(String id) {
        return Optional.ofNullable(kinds.get(id)).map(registered -> registered.owner().getName());
    }

    public List<PlacedScreen> placed() {
        return List.copyOf(placed);
    }

    /** Reads where the screens stand, off the server thread (the file may be a row in Core's database), then starts. */
    public void start() {
        Scheduling.async(plugin, () -> {
            List<PlacedScreen> read = file.read().getStringList("screens").stream().map(PlacedScreen::parse)
                    .flatMap(Optional::stream).toList();
            placed.addAll(read);
            timer = Scheduling.asyncTimer(plugin, 3, SECONDS, task -> refresh());
        });
    }

    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        for (PlacedScreen screen : placed) {
            Live gone = live.remove(screen.number());
            if (gone != null) {
                location(screen).ifPresent(at -> Scheduling.region(plugin, at, () -> clear(gone)));
            }
        }
    }

    /** A new screen of {@code kind} at {@code at}. */
    public synchronized PlacedScreen place(Location at, ScreenKind kind) {
        PlacedScreen screen = new PlacedScreen(rule.nextNumber(placed), at.getWorld().getName(), at.getX(), at.getY(),
                at.getZ(), kind.id());
        placed.add(screen);
        save();
        Scheduling.async(plugin, this::refresh);
        return screen;
    }

    /**
     * The screen meant: by number, or the nearest to {@code near} within {@value #REACH} blocks.
     */
    public Optional<PlacedScreen> find(Location near, Integer number) {
        if (number != null) {
            return placed.stream().filter(screen -> screen.number() == number).findFirst();
        }
        return rule.nearest(placed, near.getWorld().getName(), near.getX(), near.getY(), near.getZ(), REACH);
    }

    public synchronized boolean remove(PlacedScreen screen) {
        boolean removed = placed.removeIf(each -> each.number() == screen.number());
        if (removed) {
            save();
            Live gone = live.remove(screen.number());
            if (gone != null) {
                location(screen).ifPresent(at -> Scheduling.region(plugin, at, () -> clear(gone)));
            }
        }
        return removed;
    }

    /** Moves a screen up (positive) or down; the moved screen. */
    public PlacedScreen raise(PlacedScreen screen, double by) {
        return replace(screen, screen.raised(by));
    }

    /** Moves a screen to {@code to}; the moved screen. */
    public PlacedScreen move(PlacedScreen screen, Location to) {
        return replace(screen, screen.at(to.getWorld().getName(), to.getX(), to.getY(), to.getZ()));
    }

    private synchronized PlacedScreen replace(PlacedScreen screen, PlacedScreen moved) {
        placed.replaceAll(each -> each.number() == screen.number() ? moved : each);
        save();
        // Taken down where it stood and put up again where it stands now, on the next refresh.
        Live old = live.remove(screen.number());
        if (old != null) {
            location(screen).ifPresent(at -> Scheduling.region(plugin, at, () -> clear(old)));
        }
        Scheduling.async(plugin, this::refresh);
        return moved;
    }

    private void refresh() {
        for (PlacedScreen screen : placed) {
            Registered registered = kinds.get(screen.kind());
            Optional<Location> at = location(screen);
            if (at.isEmpty()) {
                continue;
            }
            Location where = at.get();
            if (registered == null) {
                Live gone = live.remove(screen.number());
                if (gone != null) {
                    Scheduling.region(plugin, where, () -> clear(gone));
                }
                continue;
            }
            ScreenKind kind = registered.kind();
            if (!kind.personal()) {
                Component text = render(kind, null);
                Scheduling.region(plugin, where, () -> showShared(screen, where, text));
                continue;
            }
            Scheduling.region(plugin, where, () -> {
                if (!where.isChunkLoaded()) {
                    Live gone = live.remove(screen.number());
                    if (gone != null) {
                        clear(gone);
                    }
                    return;
                }
                List<ScreenKind.Viewer> viewers = new ArrayList<>();
                for (Player near : where.getNearbyPlayers(RANGE)) {
                    viewers.add(new ScreenKind.Viewer(near.getUniqueId(), near.getName()));
                }
                Scheduling.async(plugin, () -> {
                    Map<UUID, Component> texts = new HashMap<>();
                    viewers.forEach(viewer -> texts.put(viewer.id(), render(kind, viewer)));
                    Scheduling.region(plugin, where, () -> showPersonal(screen, where, texts));
                });
            });
        }
    }

    private Component render(ScreenKind kind, ScreenKind.Viewer viewer) {
        try {
            Component text = kind.text().apply(viewer);
            failing.remove(kind.id());
            return text == null ? Component.empty() : text;
        } catch (RuntimeException broken) {
            if (failing.add(kind.id())) {
                log.warn("Screen kind '{}' failed to make its text: {}", kind.id(), broken.toString());
            }
            return MINI.deserialize("<gray>…");
        }
    }

    private void showShared(PlacedScreen screen, Location where, Component text) {
        if (!stillPlaced(screen) || !where.isChunkLoaded()) {
            return;
        }
        Live state = live.computeIfAbsent(screen.number(), number -> new Live());
        if (state.shared == null || !state.shared.isValid()) {
            state.shared = spawn(where, true);
        }
        state.shared.text(text);
    }

    private void showPersonal(PlacedScreen screen, Location where, Map<UUID, Component> texts) {
        if (!stillPlaced(screen) || !where.isChunkLoaded()) {
            return;
        }
        Live state = live.computeIfAbsent(screen.number(), number -> new Live());
        state.personal.entrySet().removeIf(entry -> !entry.getValue().isValid());
        ScreenRule.Viewers change = rule.viewers(new HashSet<>(state.personal.keySet()), texts.keySet());
        for (UUID gone : change.remove()) {
            TextDisplay display = state.personal.remove(gone);
            if (display != null) {
                display.remove();
            }
        }
        for (Map.Entry<UUID, Component> each : texts.entrySet()) {
            Player viewer = server.getPlayer(each.getKey());
            if (viewer == null) {
                continue;
            }
            TextDisplay display = state.personal.get(each.getKey());
            if (display == null) {
                display = spawn(where, false);
                viewer.showEntity(plugin, display);
                state.personal.put(each.getKey(), display);
            }
            display.text(each.getValue());
        }
    }

    private boolean stillPlaced(PlacedScreen screen) {
        return placed.contains(screen);
    }

    private TextDisplay spawn(Location where, boolean everybody) {
        Location spot = where.clone();
        spot.setYaw(0);
        spot.setPitch(0);
        return where.getWorld().spawn(spot, TextDisplay.class, display -> {
            display.setPersistent(false);
            display.setVisibleByDefault(everybody);
            display.setBillboard(Display.Billboard.VERTICAL);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setBackgroundColor(Color.fromARGB(150, 10, 6, 20));
            display.setShadowed(true);
            display.setLineWidth(240);
        });
    }

    private static void clear(Live state) {
        if (state.shared != null && state.shared.isValid()) {
            state.shared.remove();
        }
        state.personal.values().forEach(display -> {
            if (display.isValid()) {
                display.remove();
            }
        });
        state.personal.clear();
    }

    private Optional<Location> location(PlacedScreen screen) {
        World world = server.getWorld(screen.world());
        return world == null ? Optional.empty()
                : Optional.of(new Location(world, screen.x(), screen.y(), screen.z()));
    }

    /** Written off the server thread, for the same reason it is read off it. */
    private void save() {
        List<String> lines = placed.stream().map(PlacedScreen::line).toList();
        Scheduling.async(plugin, () -> file.write(yaml -> yaml.set("screens", lines)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID gone = event.getPlayer().getUniqueId();
        for (PlacedScreen screen : placed) {
            Live state = live.get(screen.number());
            if (state != null && state.personal.containsKey(gone)) {
                location(screen).ifPresent(at -> Scheduling.region(plugin, at, () -> {
                    TextDisplay display = state.personal.remove(gone);
                    if (display != null && display.isValid()) {
                        display.remove();
                    }
                }));
            }
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        kinds.values().removeIf(registered -> registered.owner().equals(event.getPlugin()));
    }
}
