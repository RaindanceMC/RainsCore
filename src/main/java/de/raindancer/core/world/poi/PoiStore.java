package de.raindancer.core.world.poi;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.util.Marks;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Every place any plugin has asked to remember.
 *
 * <h2>Why one store</h2>
 * There were three, behind three records with the same fields: homes had its own YAML, the ghast
 * lines had theirs, the teleport module had a third. Three answers to the same questions — what
 * happens when a world is gone, when a file is half-written, when two threads save at once — and
 * only one of the three had good answers to all of them. One store means a place saved by any plugin
 * can be listed, flown to or drawn on a map by any other, which is what makes a ghast line able to
 * fly somebody to their own home.
 *
 * <h2>Writing</h2>
 * Changes are held in memory and written when {@link #flush()} is called — on a timer, and at
 * shutdown. Writing on every change would put a disk write on the main thread every time somebody
 * sets a home; writing only at shutdown would lose everything on a crash. {@link #isDirty()} means
 * an idle server writes nothing at all.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. The places are a {@link ConcurrentHashMap}, and a flush takes a snapshot
 * rather than holding a lock across the disk write.
 */
public final class PoiStore {

    private static final LogChannel log = Log.of("poi");

    private final Database database;
    private final Map<String, Poi> places = new ConcurrentHashMap<>();
    /** Which places have changed, so a save writes one row rather than every row. */
    private final Set<String> changed = ConcurrentHashMap.newKeySet();
    /** Which places were deleted and still have a row to remove. */
    private final Set<String> deleted = ConcurrentHashMap.newKeySet();
    private final List<String> problems = new ArrayList<>();

    public PoiStore(Database database) {
        this.database = database;
    }

    // ---------------------------------------------------------------------------- writing

    /** Saves a place, replacing any with the same id. */
    public void save(Poi place) {
        if (place == null) {
            return;
        }
        // A new place on a network belongs to the server it was set on. Never re-stamped: a place
        // another server made and this one edits stays that server's.
        if (place.server().isBlank() && !de.raindancer.core.platform.network.ThisServer.id().isBlank()) {
            place = place.onServer(de.raindancer.core.platform.network.ThisServer.id());
        }
        places.put(place.id(), place);
        changed.add(place.id());
        deleted.remove(place.id());
        soon();
    }

    /** Where a write is handed the moment something changes; null leaves it to the saving timer. */
    private volatile Consumer<Runnable> writeSoon;

    /**
     * Has every change written straight away through {@code writeSoon} — Core passes its async
     * scheduler. Left to the two-minute saving timer, a home set or a warp deleted just before a crash
     * was simply undone.
     */
    public void writeSoon(Consumer<Runnable> writeSoon) {
        this.writeSoon = writeSoon;
    }

    private void soon() {
        Consumer<Runnable> soon = writeSoon;
        if (soon != null) {
            soon.accept(this::flush);
        }
    }

    /** Forgets one. Answers whether there was anything to forget. */
    public boolean delete(String id) {
        if (id == null || places.remove(id) == null) {
            return false;
        }
        forget(id);
        return true;
    }

    /** Forgets everything one player owns — for a player who has been removed from the server. */
    public int deleteAllOwnedBy(UUID owner) {
        if (owner == null) {
            return 0;
        }
        List<String> theirs = places.values().stream()
                .filter(place -> owner.equals(place.owner()))
                .map(Poi::id)
                .toList();
        theirs.forEach(places::remove);
        theirs.forEach(this::forget);
        return theirs.size();
    }

    /**
     * Notes that a place is gone and its row has to go too.
     *
     * <p>Taken off the changed list at the same time: a place saved and then deleted before the next
     * write must not be written and then deleted, which would leave the row behind if the delete half
     * failed.
     */
    private void forget(String id) {
        changed.remove(id);
        deleted.add(id);
        soon();
    }

    // ---------------------------------------------------------------------------- reading

    public Optional<Poi> byId(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(places.get(id));
    }

    /**
     * One person's place of one kind, by name.
     *
     * <p>Case-insensitive, because a player who typed {@code /home Base} meant the one they made as
     * {@code base} and telling them otherwise is pedantry.
     */
    public Optional<Poi> named(UUID owner, String kind, String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        return places.values().stream()
                .filter(place -> matches(place, owner, kind))
                .filter(place -> place.name().toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }

    /** Everything one person owns, of one kind, in the order they were made. */
    public List<Poi> owned(UUID owner, String kind) {
        return owner == null ? List.of() : filtered(place -> matches(place, owner, kind));
    }

    /** Everything one person owns, whatever kind. */
    public List<Poi> owned(UUID owner) {
        return owned(owner, null);
    }

    /** Everything of one kind, whoever owns it. */
    public List<Poi> ofKind(String kind) {
        return kind == null ? List.of() : filtered(place -> kind.equals(place.kind()));
    }

    /** Everything whose owner has shared it. */
    public List<Poi> shared() {
        return filtered(Poi::isShared);
    }

    /** Everything in one world — so a world being removed can be dealt with deliberately. */
    public List<Poi> inWorld(String world) {
        return world == null ? List.of() : filtered(place -> world.equals(place.world()));
    }

    /** Everything, in no particular order beyond insertion. */
    public List<Poi> all() {
        return filtered(place -> true);
    }

    /** How many one person has of a kind — for a limit. */
    public int count(UUID owner, String kind) {
        return owned(owner, kind).size();
    }

    /** What could not be read from the file, in the words the log used. */
    public synchronized List<String> problems() {
        return List.copyOf(problems);
    }

    private List<Poi> filtered(Predicate<Poi> test) {
        return places.values().stream().filter(test).toList();
    }

    private static boolean matches(Poi place, UUID owner, String kind) {
        if (owner != null && !owner.equals(place.owner())) {
            return false;
        }
        return kind == null || kind.equals(place.kind());
    }

    // ---------------------------------------------------------------------------- the file

    /** Whether anything has changed since the last write. An idle server writes nothing. */
    /** Whether anything is waiting to be written. */
    public boolean isDirty() {
        return !changed.isEmpty() || !deleted.isEmpty();
    }

    /** Reads the file. A missing one is an empty store, which is what a first run is. */
    /**
     * Reads every place on the server.
     *
     * <p>Must be called off the server's threads.
     */
    public void load() {
        places.clear();
        changed.clear();
        deleted.clear();
        synchronized (this) {
            problems.clear();
        }
        if (!database.isUsable()) {
            note("the database is not available, so no places were loaded");
            return;
        }
        boolean read = database.read(connection -> {
            Map<String, Map<String, String>> tags = readTags(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, name, kind, owner, world, x, y, z, yaw, pitch, icon, label, shared, server
                    FROM place""");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString("id");
                    try {
                        Poi place = readOne(rows, tags.getOrDefault(id, Map.of()));
                        places.put(place.id(), place);
                    } catch (RuntimeException broken) {
                        // One bad row is one lost place, not a lost server. The rest still load.
                        note("'" + id + "' could not be read and was skipped ("
                                + broken.getMessage() + ")");
                    }
                }
            }
            return true;
        }).orElse(false);
        if (!read) {
            note("the places could not be read");
        }
    }

    /**
     * Every place's tags, in one query.
     *
     * <p>Rather than one query per place, which on a server with a few thousand homes is a few
     * thousand queries to open a menu.
     */
    private static Map<String, Map<String, String>> readTags(Connection connection)
            throws SQLException {
        Map<String, Map<String, String>> tags = new LinkedHashMap<>();
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT place, name, value FROM place_tag");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                tags.computeIfAbsent(rows.getString("place"), key -> new LinkedHashMap<>())
                        .put(rows.getString("name"), rows.getString("value"));
            }
        }
        return tags;
    }

    private static Poi readOne(ResultSet rows, Map<String, String> tags)
            throws SQLException {
        String name = rows.getString("name");
        String world = rows.getString("world");
        if (name == null || world == null) {
            throw new IllegalArgumentException("it has no name or no world");
        }
        Poi.Builder built = Poi.builder(name, world,
                        rows.getDouble("x"), rows.getDouble("y"), rows.getDouble("z"))
                .id(rows.getString("id"))
                .kind(rows.getString("kind"))
                .facing(rows.getFloat("yaw"), rows.getFloat("pitch"))
                .label(rows.getString("label"))
                .shared(rows.getBoolean("shared"))
                .server(rows.getString("server"));
        String owner = rows.getString("owner");
        if (owner != null && !owner.isBlank()) {
            built.owner(UUID.fromString(owner));
        }
        String icon = rows.getString("icon");
        if (icon != null && !icon.isBlank()) {
            // A block renamed between versions, or one a newer server knows about, is "no icon"
            // rather than a row that will not load.
            built.icon(Material.matchMaterial(icon));
        }
        tags.forEach(built::tag);
        return built.build();
    }

    private void note(String problem) {
        synchronized (this) {
            problems.add(problem);
        }
        log.warn("{}: {}", database.file() == null ? "places" : database.file().getFileName(),
                problem);
    }

    /**
     * Writes, if anything changed.
     *
     * <p>Written to a temporary file and moved into place, so a server killed mid-write has either
     * the old file or the new one and never half of each — which is how a store of everybody's homes
     * gets truncated.
     *
     * @return whether everything is now on disk. Worth checking by anything about to delete the place
     *         it read from: a failed write puts the marks back and tries again later, but a caller
     *         that had already thrown away its source would have nothing left to try with
     */
    public boolean flush() {
        synchronized (flushing) {
            return writeChanges();
        }
    }

    /**
     * One flush at a time. The saving timer can still be mid-flush when shutdown flushes by hand, and
     * two at once either land out of order or answer "written" while the other still holds the rows.
     */
    private final Object flushing = new Object();

    private boolean writeChanges() {
        if (!isDirty()) {
            // Nothing to write is not a failure. A caller that treated it as one would refuse to
            // finish work that had already finished.
            return true;
        }
        if (!database.isUsable()) {
            return false;
        }
        // Drained, not snapshotted.
        //
        // The obvious version takes a copy of the marks, writes those rows, and then clears the marks
        // with removeAll(copy). That loses a change which arrives while the write is running: the key
        // is already in the copy, so marking it again is a no-op, and removeAll then takes the mark
        // away. The change is never written, and nothing writes it again until somebody happens to
        // touch that row once more.
        //
        // Taking the marks *off* first inverts it. A change arriving during the write puts its key
        // back, and the next flush picks it up. The worst case is one row written twice, which costs
        // nothing.
        Set<String> writing = Marks.drain(changed);
        Set<String> removing = Marks.drain(deleted);
        inFlight.addAll(writing);
        inFlight.addAll(removing);
        try {
            return writeAndAnnounce(writing, removing);
        } finally {
            inFlight.removeAll(writing);
            inFlight.removeAll(removing);
        }
    }

    /** Places being written right now: a refresh in that moment keeps them, as it keeps unwritten ones. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private boolean writeAndAnnounce(Set<String> writing, Set<String> removing) {
        List<Poi> rows = writing.stream().map(places::get)
                .filter(Objects::nonNull)
                .toList();

        boolean written = database.write(connection -> {
            try (PreparedStatement remove =
                         connection.prepareStatement("DELETE FROM place WHERE id = ?")) {
                for (String id : removing) {
                    remove.setString(1, id);
                    remove.executeUpdate();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO place (id, name, kind, owner, world, x, y, z, yaw, pitch,
                                       icon, label, shared, server)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        name = excluded.name, kind = excluded.kind, owner = excluded.owner,
                        world = excluded.world, x = excluded.x, y = excluded.y, z = excluded.z,
                        yaw = excluded.yaw, pitch = excluded.pitch, icon = excluded.icon,
                        label = excluded.label, shared = excluded.shared, server = excluded.server""");
                 PreparedStatement clearTags =
                         connection.prepareStatement("DELETE FROM place_tag WHERE place = ?");
                 PreparedStatement addTag = connection.prepareStatement(
                         "INSERT INTO place_tag (place, name, value) VALUES (?, ?, ?)")) {
                for (Poi place : rows) {
                    statement.setString(1, place.id());
                    statement.setString(2, place.name());
                    statement.setString(3, place.kind());
                    statement.setString(4, place.owner() == null ? null : place.owner().toString());
                    statement.setString(5, place.world());
                    statement.setDouble(6, place.x());
                    statement.setDouble(7, place.y());
                    statement.setDouble(8, place.z());
                    statement.setFloat(9, place.yaw());
                    statement.setFloat(10, place.pitch());
                    statement.setString(11, place.icon() == null ? null : place.icon().name());
                    // Only a label that is actually one. Poi.label() answers the *name* when no
                    // label was set, so storing what it returns would turn "no label" into a label
                    // equal to the name — and the place that comes back would differ from the one
                    // that went in, for ever, on the very first save.
                    statement.setString(12,
                            place.label().equals(place.name()) ? null : place.label());
                    statement.setInt(13, place.isShared() ? 1 : 0);
                    statement.setString(14, place.server());
                    statement.executeUpdate();

                    // Replaced wholesale rather than merged: a tag the caller removed has to
                    // disappear, and working out which ones went is more code than rewriting three
                    // rows. Safe because it is inside the same transaction as the place itself.
                    clearTags.setString(1, place.id());
                    clearTags.executeUpdate();
                    for (Map.Entry<String, String> tag : place.tags().entrySet()) {
                        addTag.setString(1, place.id());
                        addTag.setString(2, tag.getKey());
                        addTag.setString(3, tag.getValue());
                        addTag.executeUpdate();
                    }
                }
            }
        });
        if (!written) {
            // Put back, so the next flush tries again. Dropping them here would be losing the
            // places: nothing else would ever write them.
            Marks.restore(changed, writing);
            Marks.restore(deleted, removing);
            return false;
        }
        de.raindancer.core.platform.network.NetworkBus network = bus;
        if (network != null) {
            inFlight.removeAll(writing);
            inFlight.removeAll(removing);
            writing.forEach(this::refresh);
            removing.forEach(this::refresh);
            writing.forEach(id -> network.publish(TOPIC, id));
            removing.forEach(id -> network.publish(TOPIC, id));
        }
        return true;
    }

    // ---------------------------------------------------------------------------- the network

    /** The topic other servers are told on, keyed by the place's id. */
    public static final String TOPIC = "place";

    private volatile de.raindancer.core.platform.network.NetworkBus bus;

    /** Joins the network: a warp set on one server is listed on all of them, a deleted home is gone everywhere. */
    public void connect(de.raindancer.core.platform.network.NetworkBus network) {
        this.bus = network;
        if (network != null) {
            network.subscribe(TOPIC, event -> refresh(event.key()));
        }
    }

    /**
     * Reads one place from the database again: changed, added, or gone. A change of this server's that is
     * not written yet wins, and an unreadable database leaves what is known. Off the server's threads.
     */
    public void refresh(String id) {
        if (id == null || id.isBlank() || !database.isUsable()) {
            return;
        }
        Optional<Optional<Poi>> stored = database.read(connection -> {
            Map<String, String> tags = new LinkedHashMap<>();
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT name, value FROM place_tag WHERE place = ?")) {
                statement.setString(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        tags.put(rows.getString("name"), rows.getString("value"));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, name, kind, owner, world, x, y, z, yaw, pitch, icon, label, shared, server
                    FROM place WHERE id = ?""")) {
                statement.setString(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.<Poi>empty();
                    }
                    try {
                        return Optional.of(readOne(rows, tags));
                    } catch (RuntimeException broken) {
                        note("'" + id + "' could not be read and was skipped (" + broken.getMessage() + ")");
                        return Optional.<Poi>empty();
                    }
                }
            }
        });
        if (stored.isEmpty()) {
            return;
        }
        places.compute(id, (key, current) -> changed.contains(id) || deleted.contains(id) || inFlight.contains(id)
                ? current : stored.get().orElse(null));
    }
}
