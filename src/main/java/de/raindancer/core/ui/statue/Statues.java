package de.raindancer.core.ui.statue;

import com.destroystokyo.paper.profile.PlayerProfile;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.npc.Attendants;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * Statues in the world for every offered {@link StatueBoard}: a ranking's top three on pedestals under a label, a
 * thank-you board's people on the ground, taking turns every {@link #TURN} when there are more of them than statues.
 * Where they stand is {@link StatueSpots}; everything standing on a spot is tagged with its key, so a lost piece is
 * put back on the next refresh and a piece nobody owns any more is taken away. Everything touching the world happens
 * on the region that owns it; the boards are asked off the server thread.
 *
 * <p>Moved here from Economy (0.78.0) when Prime and Ranks wanted statues of their own. Pieces Economy tagged are
 * recognised and re-tagged, so the statues standing then are kept, not put up twice.
 */
public final class Statues {

    /** On every piece of a statue: its spot's key, then {@code #statue}, {@code #pedestal} or {@code #label}. */
    public static final NamespacedKey PIECE = Objects.requireNonNull(NamespacedKey.fromString("rainscore:statue"));
    /** On a statue: whose skin it wears, so a refresh changes it only when the place changes hands. */
    public static final NamespacedKey HOLDER = Objects.requireNonNull(NamespacedKey.fromString("rainscore:statue_holder"));
    /** Economy's tags before 0.78.0, read and replaced by the ones above. */
    static final NamespacedKey OLD_PIECE = Objects.requireNonNull(NamespacedKey.fromString("rainseconomy:statue"));
    static final NamespacedKey OLD_HOLDER = Objects.requireNonNull(NamespacedKey.fromString("rainseconomy:statue_holder"));

    /** How long a thank-you board's people stand before the next ones take their place. */
    public static final Duration TURN = Duration.ofMinutes(10);
    private static final Material[] PEDESTALS = {Material.GOLD_BLOCK, Material.IRON_BLOCK, Material.WAXED_COPPER_BLOCK};
    private static final String NOBODY = "-";
    private static final double REACH_SQUARED = 25;
    private static final long FIRST_REFRESH_SECONDS = 10;
    private static final long REFRESH_SECONDS = 5;
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LogChannel log = Log.of("core");

    private static volatile Statues installed;

    private final Plugin plugin;
    private final Server server;
    private final StatueSpots spots;
    private final Messages messages;
    private final LongSupplier clock;
    private final StatueRule rule = new StatueRule();
    private volatile ScheduledTask timer;

    /** What one refresh found for one statue, waiting to be drawn on its region. */
    private record Shown(StatueSpot spot, StatueBoard board, StatueBoard.Standing holder, boolean labelled) {
    }

    public Statues(Plugin plugin, Server server, StatueSpots spots, Messages messages, LongSupplier clock) {
        this.plugin = plugin;
        this.server = server;
        this.spots = spots;
        this.messages = messages;
        this.clock = clock;
    }

    /** Core's statues, once Core runs; empty before, after, and on a Core without them. */
    public static Optional<Statues> installed() {
        return Optional.ofNullable(installed);
    }

    /** Core, at start (and with null at its end). */
    public static void install(Statues statues) {
        installed = statues;
    }

    /** Reads where the statues stand off the server thread, then keeps them up to date. */
    public void start() {
        Scheduling.async(plugin, () -> {
            if (!spots.load()) {
                log.error("statues.yml could not be read. No statue is placed, moved or removed until it can be.");
            }
            timer = Scheduling.asyncTimer(plugin, FIRST_REFRESH_SECONDS, REFRESH_SECONDS, task -> refresh());
        });
    }

    public void stop() {
        ScheduledTask running = timer;
        if (running != null) {
            running.cancel();
            timer = null;
        }
    }

    /** Every statue, as placed. */
    public List<StatueSpot> spots() {
        return spots.all();
    }

    /**
     * Takes over the statues another plugin kept before they were Core's, once ({@link StatueSpots#adopt}). Off the
     * server thread; logged.
     */
    public void adopt(String source, List<String> lines) {
        Scheduling.async(plugin, () -> {
            StatueSpots.Adoption done = spots.adopt(source, lines);
            if (!done.saved()) {
                log.warn("The statues of {} could not be taken over (statues.yml was not written); trying again "
                        + "on the next start.", source);
                return;
            }
            if (done.taken() > 0) {
                log.info("Took over {} statue(s) from {}; they stand where they stood.", done.taken(), source);
            }
            if (done.unreadable() > 0) {
                log.warn("{} line(s) of {}'s statues could not be read and were left out.", done.unreadable(), source);
            }
            refresh();
        });
    }

    /** Asks every board once and puts every statue whose ground is loaded up to date. Off the server thread. */
    public void refresh() {
        List<StatueSpot> all = spots.all();
        if (all.isEmpty()) {
            return;
        }
        Set<String> known = new HashSet<>();
        all.forEach(spot -> known.add(spot.key()));
        Map<String, List<StatueBoard.Standing>> tops = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        all.forEach(spot -> counts.merge(spot.board(), 1, Integer::sum));
        long turn = clock.getAsLong() / TURN.toMillis();
        List<Shown> shown = new ArrayList<>();
        for (StatueSpot spot : all) {
            Optional<StatueBoard> board = StatueBoards.find(spot.board());
            if (board.isEmpty()) {
                // Its plugin is not here (yet): the statue stays exactly as it stands.
                continue;
            }
            List<StatueBoard.Standing> top = tops.computeIfAbsent(spot.board(), StatueBoards::standings);
            StatueBoard.Standing holder = board.get().thankYou()
                    ? rule.standing(top, spot.place(), counts.get(spot.board()), turn).orElse(null)
                    : spot.place() <= top.size() ? top.get(spot.place() - 1) : null;
            shown.add(new Shown(spot, board.get(), holder, rule.labelled(spot, all, board.get().thankYou())));
        }
        if (shown.isEmpty()) {
            return;
        }
        Scheduling.global(plugin, () -> {
            for (Shown each : shown) {
                World world = server.getWorld(each.spot().world());
                if (world == null) {
                    continue;
                }
                Location ground = new Location(world, each.spot().x(), each.spot().y(), each.spot().z());
                Scheduling.region(plugin, ground, () -> {
                    if (world.isChunkLoaded(ground.getBlockX() >> 4, ground.getBlockZ() >> 4)) {
                        build(each, ground, known);
                    }
                });
            }
        });
    }

    /** A piece's tag, an Economy-tagged one taken over on the way. */
    private static String tagOf(Entity piece) {
        PersistentDataContainer data = piece.getPersistentDataContainer();
        String tag = data.get(PIECE, PersistentDataType.STRING);
        if (tag != null) {
            return tag;
        }
        return data.get(OLD_PIECE, PersistentDataType.STRING);
    }

    private static void retag(Entity piece, String tag) {
        PersistentDataContainer data = piece.getPersistentDataContainer();
        if (data.has(OLD_PIECE, PersistentDataType.STRING)) {
            data.set(PIECE, PersistentDataType.STRING, tag);
            data.remove(OLD_PIECE);
            String wore = data.get(OLD_HOLDER, PersistentDataType.STRING);
            if (wore != null) {
                data.set(HOLDER, PersistentDataType.STRING, wore);
                data.remove(OLD_HOLDER);
            }
        }
    }

    private void build(Shown shown, Location ground, Set<String> known) {
        StatueSpot spot = shown.spot();
        StatueBoard board = shown.board();
        String own = spot.key() + "#";
        List<Entity> pieces = new ArrayList<>();
        for (Entity nearby : ground.getWorld().getNearbyEntities(ground, 3, 4, 3)) {
            String tag = tagOf(nearby);
            if (tag == null) {
                continue;
            }
            int cut = tag.lastIndexOf('#');
            if (cut < 0 || !known.contains(tag.substring(0, cut))) {
                // Left by a statue that was moved or removed while nobody was near, or by an old podium.
                nearby.remove();
            } else if (tag.startsWith(own)) {
                retag(nearby, tag);
                pieces.add(nearby);
            }
        }
        // Two pieces of one kind on one spot (Economy's found after Core put up its own): the first stays.
        Set<String> kinds = new HashSet<>();
        pieces.removeIf(piece -> {
            if (kinds.add(piece.getType() + " " + tagOf(piece))) {
                return false;
            }
            piece.remove();
            return true;
        });
        Optional<TextDisplay> label = find(pieces, TextDisplay.class, own + "label");
        if (shown.labelled()) {
            Location above = ground.clone().add(0, rule.labelHeight(spot.place()), 0);
            TextDisplay drawn = label.orElseGet(() -> spawnLabel(own + "label", above));
            drawn.text(MINI.deserialize(board.heading()));
            if (drawn.getLocation().distanceSquared(above) > 0.01) {
                drawn.teleportAsync(above);
            }
        } else {
            label.ifPresent(Entity::remove);
        }
        Optional<BlockDisplay> pedestal = find(pieces, BlockDisplay.class, own + "pedestal");
        if (board.thankYou()) {
            pedestal.ifPresent(Entity::remove);
        } else if (pedestal.isEmpty()) {
            spawnPedestal(ground, own + "pedestal", spot.place());
        }
        Location feet = ground.clone().add(0, rule.standingHeight(spot, board.thankYou()), 0);
        feet.setYaw(spot.yaw());
        feet.setPitch(0);
        Mannequin statue = find(pieces, Mannequin.class, own + "statue")
                .orElseGet(() -> spawnStatue(feet, spot.yaw(), own + "statue"));
        if (board.thankYou() && Math.abs(statue.getLocation().getY() - feet.getY()) > 0.1) {
            // A thank-you statue put up on a pedestal (Economy before 0.74.0) steps down; nothing else is moved here.
            statue.teleportAsync(feet);
        }
        dress(statue, spot, board, shown.holder());
    }

    private void dress(Mannequin statue, StatueSpot spot, StatueBoard board, StatueBoard.Standing holder) {
        statue.customName(MINI.deserialize(board.title(spot.place(), holder == null ? null : holder.name())));
        statue.setDescription(board.line(holder));
        String wearing = statue.getPersistentDataContainer().get(HOLDER, PersistentDataType.STRING);
        String now = holder == null ? NOBODY : holder.player().toString();
        if (!now.equals(wearing)) {
            statue.setProfile(holder == null ? Attendants.look(null) : skinOf(holder.player()));
            statue.getPersistentDataContainer().set(HOLDER, PersistentDataType.STRING, now);
        }
    }

    /** An online player's profile carries the skin already; anybody else's is looked up by id. */
    private ResolvableProfile skinOf(UUID id) {
        Player online = server.getPlayer(id);
        if (online != null) {
            PlayerProfile profile = online.getPlayerProfile();
            if (profile.hasTextures()) {
                return ResolvableProfile.resolvableProfile(profile);
            }
        }
        return ResolvableProfile.resolvableProfile().uuid(id).build();
    }

    private static <T extends Entity> Optional<T> find(List<Entity> pieces, Class<T> type, String tag) {
        for (Entity piece : pieces) {
            if (type.isInstance(piece) && tag.equals(piece.getPersistentDataContainer().get(PIECE, PersistentDataType.STRING))) {
                return Optional.of(type.cast(piece));
            }
        }
        return Optional.empty();
    }

    private static TextDisplay spawnLabel(String tag, Location above) {
        return above.getWorld().spawn(above, TextDisplay.class, display -> {
            display.setBillboard(Display.Billboard.CENTER);
            display.setPersistent(true);
            display.setShadowed(true);
            display.getPersistentDataContainer().set(PIECE, PersistentDataType.STRING, tag);
        });
    }

    private void spawnPedestal(Location ground, String tag, int place) {
        float height = (float) rule.height(place);
        // A display's model grows from its corner, so the block is set down half a block off the statue's centre.
        Location corner = ground.clone().add(-0.5, 0, -0.5);
        corner.setYaw(0);
        corner.setPitch(0);
        ground.getWorld().spawn(corner, BlockDisplay.class, display -> {
            display.setBlock(PEDESTALS[Math.min(place, PEDESTALS.length) - 1].createBlockData());
            display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(1, height, 1), new AxisAngle4f()));
            display.setPersistent(true);
            display.getPersistentDataContainer().set(PIECE, PersistentDataType.STRING, tag);
        });
    }

    private static Mannequin spawnStatue(Location at, float yaw, String tag) {
        Mannequin statue = Attendants.place(at, Component.empty(), Component.empty(), Attendants.look(null),
                placed -> {
                    placed.setGravity(false);
                    placed.getPersistentDataContainer().set(PIECE, PersistentDataType.STRING, tag);
                });
        statue.setRotation(yaw, 0);
        return statue;
    }

    private static List<Entity> piecesOf(StatueSpot spot, Location ground) {
        List<Entity> found = new ArrayList<>();
        for (Entity nearby : ground.getWorld().getNearbyEntities(ground, 3, 4, 3)) {
            String tag = tagOf(nearby);
            if (tag != null && tag.startsWith(spot.key() + "#")) {
                found.add(nearby);
            }
        }
        return found;
    }

    // ---------------------------------------------------------------------------- placing

    /**
     * A ranking's whole podium centred on the block the player stands on, the statues facing the way the player
     * looks, turned to the nearest quarter so the pedestals line up with the blocks around them. On the player's thread.
     */
    public List<StatueSpot> placePodium(Player player, StatueBoard board) {
        Location feet = player.getLocation();
        String group = UUID.randomUUID().toString().substring(0, 8);
        List<StatueSpot> three = rule.podium(feet.getWorld().getName(), feet.getBlockX() + 0.5, feet.getBlockY(),
                feet.getBlockZ() + 0.5, rule.facing(feet.getYaw(), 90f), board.id(), group);
        write(all -> {
            all.addAll(three);
            return all;
        });
        return three;
    }

    /** One more statue of a thank-you board where the player stands, in its lowest free place; empty when all stand. */
    public Optional<StatueSpot> placeNext(Player player, StatueBoard board) {
        OptionalInt next = rule.nextPlace(spots.all(), board.id());
        return next.isEmpty() ? Optional.empty() : Optional.of(placeOne(player, board, next.getAsInt()));
    }

    /** One statue for {@code place} on the block the player stands on, facing the nearest eighth of their look. */
    public StatueSpot placeOne(Player player, StatueBoard board, int place) {
        StatueSpot spot = here(player, new StatueSpot("-", 0, 0, 0, 0, board.id(), place, StatueSpot.ALONE));
        write(all -> {
            all.add(spot);
            return all;
        });
        return spot;
    }

    /**
     * Brings the nearest statue of {@code board} and {@code place} in the player's world to where they stand; it
     * leaves its podium, if it had one. Whether there was one.
     */
    public boolean move(Player player, String board, int place) {
        Location at = player.getLocation();
        Optional<StatueSpot> nearest = rule.nearest(spots.all(), at.getWorld().getName(), at.getX(), at.getY(),
                at.getZ(), Double.MAX_VALUE, spot -> spot.board().equals(board) && spot.place() == place);
        if (nearest.isEmpty()) {
            return false;
        }
        StatueSpot old = nearest.get();
        StatueSpot moved = here(player, old);
        write(all -> {
            all.replaceAll(spot -> spot.equals(old) ? moved : spot);
            return all;
        });
        clear(old);
        return true;
    }

    private StatueSpot here(Player player, StatueSpot spot) {
        Location feet = player.getLocation();
        StatueSpot there = spot.at(feet.getWorld().getName(), feet.getBlockX() + 0.5, feet.getBlockY(),
                feet.getBlockZ() + 0.5, rule.facing(feet.getYaw(), 45f));
        return StatueSpot.parse(there.written()).orElseThrow();
    }

    /**
     * Takes away the nearest statue within five blocks; with {@code wholePodium}, every statue of the podium it
     * stands on. Whether there was one.
     */
    public boolean remove(Player player, boolean wholePodium) {
        Location at = player.getLocation();
        List<StatueSpot> all = spots.all();
        Optional<StatueSpot> nearest = rule.nearest(all, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                REACH_SQUARED, spot -> true);
        if (nearest.isEmpty()) {
            return false;
        }
        StatueSpot hit = nearest.get();
        List<StatueSpot> gone = wholePodium && !hit.standalone()
                ? all.stream().filter(spot -> spot.group().equals(hit.group())).toList() : List.of(hit);
        write(now -> {
            now.removeAll(gone);
            return now;
        });
        gone.forEach(this::clear);
        return true;
    }

    private void clear(StatueSpot spot) {
        World world = server.getWorld(spot.world());
        if (world == null) {
            return;
        }
        Location there = new Location(world, spot.x(), spot.y(), spot.z());
        Scheduling.region(plugin, there, () -> piecesOf(spot, there).forEach(Entity::remove));
    }

    /** Changes the statues and saves them off the server thread (the store is a database row), then builds them. */
    private void write(UnaryOperator<List<StatueSpot>> change) {
        Scheduling.async(plugin, () -> {
            if (spots.change(change)) {
                refresh();
            } else {
                log.error("A statue change could not be written to statues.yml and was not kept.");
            }
        });
    }

    // ---------------------------------------------------------------------------- the command

    /**
     * The statue command for whichever plugin's command hands it on ({@code /eco statue}):
     * {@code place [board] [place]} puts up a ranking's podium, a thank-you board's next statue, or one statue of a
     * place; {@code move <board> <place>} brings the nearest such statue here; {@code remove [podium]} takes away the
     * nearest statue, or the whole podium it stands on. On the player's thread.
     *
     * @param command  what was typed before these, for the messages ({@code /eco statue})
     * @param args     what follows it
     * @param fallback the board {@code place} puts up when none is named
     */
    public void command(Player player, String command, String[] args, String fallback) {
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "place";
        if (action.equals("remove")) {
            boolean whole = args.length > 1 && args[1].equalsIgnoreCase("podium");
            messages.send(player, remove(player, whole) ? whole ? "statue.podium-removed" : "statue.removed"
                    : "statue.none");
            return;
        }
        boolean moving = action.equals("move");
        if (!moving && !action.equals("place")) {
            messages.send(player, "statue.usage", "command", command, "boards", String.join("|", StatueBoards.ids()));
            return;
        }
        String typed = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : moving ? "" : fallback;
        Optional<StatueBoard> found = StatueBoards.find(typed);
        if (found.isEmpty()) {
            if (moving && typed.isEmpty()) {
                messages.send(player, "statue.usage", "command", command, "boards",
                        String.join("|", StatueBoards.ids()));
            } else {
                messages.send(player, "statue.not-a-board", "what", typed, "boards", boardList());
            }
            return;
        }
        StatueBoard board = found.get();
        if (!moving && args.length <= 2) {
            if (board.thankYou()) {
                placeNext(player, board).ifPresentOrElse(
                        spot -> messages.send(player, "statue.placed-thanks", "place", spot.place(), "ranks",
                                board.ranks(), "command", command, "board", board.id(), "most", StatueSpot.MOST),
                        () -> messages.send(player, "statue.full", "most", StatueSpot.MOST, "ranks", board.ranks(),
                                "command", command));
            } else {
                placePodium(player, board);
                messages.send(player, "statue.placed-podium", "ranks", board.ranks(), "command", command);
            }
            return;
        }
        String typedPlace = args.length > 2 ? args[2] : "";
        Optional<Integer> place = StatueSpot.readPlace(board.thankYou(), typedPlace);
        if (place.isEmpty()) {
            messages.send(player, "statue.not-a-place", "what", typedPlace, "ranks", board.ranks(), "most",
                    board.thankYou() ? StatueSpot.MOST : StatueSpot.PODIUM);
            return;
        }
        if (moving) {
            messages.send(player, move(player, board.id(), place.get()) ? "statue.moved" : "statue.not-found",
                    "place", place.get(), "ranks", board.ranks(), "command", command);
            return;
        }
        placeOne(player, board, place.get());
        messages.send(player, "statue.placed-one", "place", place.get(), "ranks", board.ranks(), "command", command);
    }

    /** {@code casino}, {@code prime} or {@code richest}: the boards offered, for a sentence. */
    private static String boardList() {
        List<String> ids = StatueBoards.ids();
        if (ids.isEmpty()) {
            return "none here";
        }
        if (ids.size() == 1) {
            return ids.getFirst();
        }
        return String.join(", ", ids.subList(0, ids.size() - 1)) + " or " + ids.getLast();
    }

    /** What to offer after the command for what is typed so far ({@code args} as {@link #command} gets them). */
    public static List<String> suggest(String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            return starting(args[0], List.of("place", "move", "remove"));
        }
        boolean placing = args[0].equalsIgnoreCase("place") || args[0].equalsIgnoreCase("move");
        if (args.length == 2) {
            return placing ? starting(args[1], StatueBoards.ids())
                    : args[0].equalsIgnoreCase("remove") ? starting(args[1], List.of("podium")) : List.of();
        }
        if (args.length == 3 && placing) {
            return StatueBoards.find(args[1].toLowerCase(Locale.ROOT)).filter(board -> !board.thankYou())
                    .map(board -> starting(args[2], List.of("1", "2", "3"))).orElse(List.of());
        }
        return List.of();
    }

    private static List<String> starting(String typed, List<String> options) {
        String lower = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lower)).toList();
    }
}
