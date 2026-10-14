package de.raindancer.core.data.store;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A YAML document, read and written without losing it — a row in the database once Core runs (2.0.0),
 * a file on disk otherwise.
 *
 * <h2>In the database</h2>
 * With Core running, a store whose file is under the plugins folder keeps its document in Core's database
 * instead ({@link Documents}), named by that path. The first time it is used, an existing 1.x file is
 * brought in and renamed {@code *.imported-2.0.0}. Writes state the version they read; when another
 * server wrote in between, this write is merged onto theirs value by value and tried again. A document is
 * this server's by default — as its file was — or the whole network's ({@link #network(Path)}).
 * Everything below about files applies to a file outside the plugins folder, and to tests without Core.
 *
 * <h2>Why this exists</h2>
 * Because the same twenty lines were written seven times in this library alone — {@code PoiStore},
 * {@code CustomItems}, {@code Punishments}, {@code Identities}, {@code Achievements} and
 * {@code LootTables} each had their own copy of the write-to-a-temporary-then-move dance — and
 * again in every plugin that keeps anything, warp-module's {@code WarpRegistry} and
 * farmworld-module's {@code FarmWorldState} among them now. A library whose whole point is removing
 * duplication was the worst offender in its own family. A review said so and was right.
 *
 * <p>The repetition is the smaller half. Each copy is a chance to get the write-and-move wrong, and
 * getting it wrong means a server killed at the wrong moment has half a file where everybody's
 * homes used to be. Written once, tested once, and every store gets the same guarantees.
 *
 * <h2>What it guarantees</h2>
 * <ul>
 *   <li>The real file is only ever replaced by a finished one. A kill mid-write leaves the old file
 *       or the new one, never half of each.</li>
 *   <li>A write that throws halfway leaves the previous contents untouched.</li>
 *   <li>No temporary is left behind, whether the write worked or not.</li>
 *   <li>A file that will not parse reads as empty and <em>says so</em>, rather than looking like a
 *       server that has lost everybody's data.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Writes are serialised on this object. Two callers writing at once produce one file and one
 * winner, rather than two half-writes interleaved — which is what the seven separate copies never
 * promised.
 */
public final class YamlStore {

    private static final LogChannel log = Log.of("store");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    private final Path file;
    /**
     * Shared by every store on the same file. Two settings records of one module are two stores on one
     * config.yml; locked each on its own, two writes at once raced over the same {@code .writing} file and
     * one of them was lost.
     */
    private final Object lock;
    private static final java.util.concurrent.ConcurrentMap<Path, Object> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> problems = new ArrayList<>();
    /** Whether the last read found a file it could not parse — one a write must not replace. */
    private boolean unreadable;

    /** Set when the document lives in the database; null keeps it a file. */
    private final Documents documents;
    private final Documents.Address address;

    /** What this store last read or wrote of its document — the base a write is merged from. */
    private volatile String baseBody = "";
    private volatile long baseVersion;
    private volatile boolean baseKnown;

    /** How often a write is tried again when other writers keep getting in first. */
    private static final int ATTEMPTS = 8;
    private static final String IMPORTED = ".imported-2.0.0";

    /** This server's document, kept in the database when Core runs; a file otherwise. */
    public YamlStore(Path file) {
        this(file, Documents.installed().orElse(null), Documents.Scope.SERVER);
    }

    /** One document for the whole network: every server reads and writes the same one. */
    public static YamlStore network(Path file) {
        return new YamlStore(file, Documents.installed().orElse(null), Documents.Scope.NETWORK);
    }

    /** A store on {@code documents} rather than the installed ones — for tests, and for Core itself. */
    public YamlStore(Path file, Documents documents, Documents.Scope scope) {
        this.lock = file == null ? new Object()
                : LOCKS.computeIfAbsent(file.toAbsolutePath().normalize(), key -> new Object());
        this.file = file;
        this.documents = documents;
        this.address = documents == null || file == null ? null
                : documents.addressOf(file, scope == null ? Documents.Scope.SERVER : scope).orElse(null);
    }

    /** Whether the document is kept in the database rather than in its file. */
    public boolean inDatabase() {
        return address != null;
    }

    /**
     * Runs {@code listener} whenever the document is written by somebody else — another store on it, or
     * another server of the network. For a store that keeps what it read and has to read it again.
     */
    public de.raindancer.core.platform.network.NetworkBus.Subscription onChange(Runnable listener) {
        if (address == null) {
            return () -> { };
        }
        // Not for this store's own writes: it knows what it wrote, and reloading in the middle of its own
        // write would throw away whatever it changed since.
        return documents.listen(address, () -> {
            if (WRITING.get() != this) {
                listener.run();
            }
        });
    }

    /** The store writing on this thread right now, so its own listener can tell its own write from others'. */
    private static final ThreadLocal<YamlStore> WRITING = new ThreadLocal<>();

    public Path file() {
        return file;
    }

    public boolean exists() {
        if (address != null) {
            bringInFile();
            return documents.read(address) instanceof Documents.Found;
        }
        return file != null && Files.isRegularFile(file);
    }

    /** What was wrong with the file last time it was read. Empty when it was clean. */
    public synchronized List<String> problems() {
        return List.copyOf(problems);
    }

    // ---------------------------------------------------------------------------- reading

    /**
     * The file's contents, or an empty configuration when there is not one.
     *
     * <p>Never throws and never returns null. A file that cannot be parsed comes back empty with
     * the reason in {@link #problems()} — because a plugin that refuses to start over a bad config
     * helps nobody, and one that silently treats it as empty is worse still.
     */
    public YamlConfiguration read() {
        synchronized (this) {
            problems.clear();
            unreadable = false;
        }
        if (address != null) {
            return readDocument();
        }
        if (file == null || !Files.isRegularFile(file)) {
            return new YamlConfiguration();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            String text;
            synchronized (this) {
                // Held across the read, so it cannot overlap the move in put(). Without this a
                // reader could land exactly between the two and report a perfectly good file as
                // corrupt — which is a false alarm that sends somebody looking for lost data.
                text = Files.readString(file);
            }
            yaml.loadFromString(text);
            return yaml;
        } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
            synchronized (this) {
                unreadable = true;
            }
            note("could not be read (" + failure.getMessage() + ")");
            return new YamlConfiguration();
        }
    }

    // ---------------------------------------------------------------------------- writing

    /**
     * Writes the file.
     *
     * <p>The consumer is given a fresh configuration to fill in. It runs <em>before</em> anything
     * touches the real file, so a caller that throws halfway costs nothing.
     *
     * @return whether it was written; false leaves whatever was there before
     */
    public boolean write(Consumer<YamlConfiguration> contents) {
        if (file == null || contents == null) {
            return false;
        }
        if (address != null) {
            return writeDocument(contents);
        }
        return put(new YamlConfiguration(), contents);
    }

    /**
     * Reads, lets the caller change what is there, and writes it back.
     *
     * <p>For a file that is not wholly ours: a settings file keeps keys a newer version of the plugin
     * added and comments somebody wrote, so writing a fresh one from what we know would quietly throw
     * those away. Reading and writing are one step here, so two callers cannot both read, both
     * change, and have the second undo the first.
     *
     * <p>A file that exists but cannot be parsed is never written: the update is refused and the
     * file left exactly as it is, for its owner to fix.
     *
     * @return whether it was written; false leaves whatever was there before
     */
    public boolean update(Consumer<YamlConfiguration> change) {
        if (file == null || change == null) {
            return false;
        }
        if (address != null) {
            return updateDocument(change);
        }
        synchronized (lock) {
            YamlConfiguration current = read();
            if (unreadable) {
                // Refused, and the file left where it is — not even set aside. An update is a change
                // to what is there; what is there could not be read, so anything written now would
                // replace it with this one change. The owner fixes the file; the next update works.
                log.error("{} could not be read, so this change was not written to it. Fix the file "
                        + "(the line above says where) and the next change will be kept.", file);
                return false;
            }
            return put(current, change);
        }
    }

    private boolean put(YamlConfiguration yaml, Consumer<YamlConfiguration> contents) {
        return put(yaml, contents, null);
    }

    /** {@code exactText}, when given, is written as it is instead of the YAML built by {@code contents}. */
    private boolean put(YamlConfiguration yaml, Consumer<YamlConfiguration> contents, String exactText) {
        synchronized (lock) {
            return putLocked(yaml, contents, exactText);
        }
    }

    private boolean putLocked(YamlConfiguration yaml, Consumer<YamlConfiguration> contents, String exactText) {
        // The contents are built under the same lock as the move, so two writers cannot each read
        // what they know and then land in the opposite order — the older picture last.
        String text;
        try {
            if (exactText != null) {
                text = exactText;
            } else {
                contents.accept(yaml);
                text = yaml.saveToString();
            }
        } catch (RuntimeException failure) {
            // Nothing has been touched yet, so there is nothing to undo — which is the reason the
            // caller's work happens before the file is opened rather than into it.
            log.error(failure, "Could not build the contents of {}; it is unchanged.", file);
            return false;
        }

        // A store that read this file as empty because it could not parse it is about to write
        // what it has — nothing — over everything somebody wrote there. Set aside first; if even
        // that fails, the write is the thing that gives way.
        if (unreadable && Files.isRegularFile(file)) {
            if (quarantine().isEmpty()) {
                log.error("Not writing {}: it could not be read, and could not be set aside either.",
                        file);
                return false;
            }
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".writing");
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(temporary, text);
            // The move is what makes this safe: on every filesystem these run on it is atomic,
            // so a reader sees the old file or the new one and never a partial write.
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException failure) {
            log.error(failure, "Could not write {}; it is unchanged.", file);
            return false;
        } finally {
            // A temporary left behind after a failure would be written over next time anyway,
            // but a directory littered with .writing files is a directory somebody worries about.
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Nothing useful to do, and nothing depends on it.
            }
        }
    }

    /**
     * A key made safe to be one part of a YAML path. A dot inside a section name would nest it — a
     * plugin's {@code wand.v2} written as {@code items.claims:wand.v2.material} reads back as an item
     * {@code claims:wand} with a child section {@code v2} — so dots are written as {@code ·}, which no
     * key uses. {@link #fromPathPart} undoes it.
     */
    public static String asPathPart(String key) {
        return key == null ? null : key.replace('.', '·');
    }

    /** The key a {@link #asPathPart} section name was written from. */
    public static String fromPathPart(String part) {
        return part == null ? null : part.replace('·', '.');
    }

    /**
     * Moves a file that could not be read out of the way, keeping it.
     *
     * <p>For a store that would otherwise write over it on the next save: the data in a corrupt file
     * is often recoverable by hand and is certainly not ours to delete. The plugin then starts empty
     * rather than refusing to start, and the old file is still there to look at.
     *
     * @return where it was put, or empty when there was nothing to move
     */
    public synchronized Optional<Path> quarantine() {
        if (address != null) {
            return quarantineDocument();
        }
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Path kept = file.resolveSibling(
                file.getFileName() + ".broken-" + STAMP.format(LocalDateTime.now()));
        try {
            Files.move(file, kept, StandardCopyOption.REPLACE_EXISTING);
            unreadable = false;
            log.warn("{} could not be read and has been kept as {} rather than written over.",
                    file.getFileName(), kept.getFileName());
            return Optional.of(kept);
        } catch (IOException failure) {
            log.error(failure, "Could not set aside the unreadable file {}", file);
            return Optional.empty();
        }
    }

    private void note(String problem) {
        synchronized (this) {
            problems.add(problem);
        }
        log.warn("{}: {}", file == null ? "?" : file.getFileName(), problem);
    }

    /** Removes the document — or the file — altogether. True when it is gone, also when it never was. */
    public boolean delete() {
        if (file == null) {
            return true;
        }
        if (address != null) {
            bringInFile();
            synchronized (lock) {
                baseKnown = false;
                return asThisStore(() -> documents.delete(address));
            }
        }
        synchronized (lock) {
            try {
                Files.deleteIfExists(file);
                return true;
            } catch (IOException failure) {
                log.error(failure, "Could not delete {}.", file);
                return false;
            }
        }
    }

    // ---------------------------------------------------------------------------- as text

    /**
     * The document as the text it is, comments and order included — for a store that edits it line by line
     * rather than as YAML. Empty when there is none or it cannot be read.
     */
    public Optional<String> readText() {
        if (address != null) {
            bringInFile();
            return documents.read(address) instanceof Documents.Found found ? Optional.of(found.body()) : Optional.empty();
        }
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            synchronized (this) {
                return Optional.of(Files.readString(file));
            }
        } catch (IOException unreadableFile) {
            note("could not be read (" + unreadableFile.getMessage() + ")");
            return Optional.empty();
        }
    }

    /** Writes the document as {@code text}, exactly — nothing parsed, nothing merged. */
    public boolean writeText(String text) {
        if (file == null || text == null) {
            return false;
        }
        if (address != null) {
            synchronized (lock) {
                for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                    Documents.Read now = documents.read(address);
                    if (now instanceof Documents.Failed) {
                        return false;
                    }
                    boolean written = now instanceof Documents.Found found
                            ? asThisStore(() -> documents.compareAndSet(address, found.version(), text))
                            : asThisStore(() -> documents.insertIfAbsent(address, text));
                    if (written) {
                        baseKnown = false;
                        return true;
                    }
                    if (!documents.isUsable()) {
                        return false;
                    }
                }
                return false;
            }
        }
        return put(new YamlConfiguration(), ignored -> { }, text);
    }

    /** Writes {@code text} only when there is no document yet; false when there is one, or on failure. */
    public boolean writeTextIfAbsent(String text) {
        if (file == null || text == null) {
            return false;
        }
        if (address != null) {
            bringInFile();
            return asThisStore(() -> documents.insertIfAbsent(address, text));
        }
        if (Files.isRegularFile(file)) {
            return false;
        }
        return put(new YamlConfiguration(), ignored -> { }, text);
    }

    // ---------------------------------------------------------------------------- in the database

    private YamlConfiguration readDocument() {
        bringInFile();
        Documents.Read read = documents.read(address);
        if (read instanceof Documents.Failed failed) {
            synchronized (this) {
                unreadable = true;
            }
            note("could not be read (" + failed.why() + ")");
            return new YamlConfiguration();
        }
        if (read instanceof Documents.Absent) {
            remember("", 0);
            return new YamlConfiguration();
        }
        Documents.Found found = (Documents.Found) read;
        Optional<YamlConfiguration> parsed = parse(found.body());
        if (parsed.isEmpty()) {
            synchronized (this) {
                unreadable = true;
            }
            note("could not be read (it is not valid YAML)");
            return new YamlConfiguration();
        }
        remember(found.body(), found.version());
        return parsed.get();
    }

    private boolean writeDocument(Consumer<YamlConfiguration> contents) {
        bringInFile();
        synchronized (lock) {
            YamlConfiguration mine = new YamlConfiguration();
            String body;
            try {
                contents.accept(mine);
                body = mine.saveToString();
            } catch (RuntimeException failure) {
                log.error(failure, "Could not build the contents of {}; it is unchanged.", address.key());
                return false;
            }
            if (unreadable && quarantineDocument().isEmpty()) {
                log.error("Not writing {}: it could not be read, and could not be set aside either.", address.key());
                return false;
            }
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                Documents.Read now = documents.read(address);
                if (now instanceof Documents.Failed failed) {
                    log.error("Could not write {} ({}); it is unchanged.", address.key(), failed.why());
                    return false;
                }
                if (now instanceof Documents.Absent) {
                    if (asThisStore(() -> documents.insertIfAbsent(address, body))) {
                        remember(body, 1);
                        return true;
                    }
                    if (!documents.isUsable()) {
                        return false;
                    }
                    continue;
                }
                Documents.Found found = (Documents.Found) now;
                String toWrite = body;
                if (baseKnown && found.version() != baseVersion) {
                    // Somebody wrote since this store read: what this store changed goes on top of theirs.
                    Optional<YamlConfiguration> theirs = parse(found.body());
                    Optional<YamlConfiguration> base = parse(baseBody);
                    if (theirs.isPresent() && base.isPresent()) {
                        YamlMerge.Result merged = YamlMerge.threeWay(base.get(), mine, theirs.get());
                        if (!merged.conflicts().isEmpty()) {
                            log.warn("{}: written over a change made elsewhere at {}", address.key(),
                                    String.join(", ", merged.conflicts()));
                        }
                        toWrite = merged.yaml().saveToString();
                    }
                }
                String merged = toWrite;
                if (asThisStore(() -> documents.compareAndSet(address, found.version(), merged))) {
                    remember(toWrite, found.version() + 1);
                    return true;
                }
                if (!documents.isUsable()) {
                    return false;
                }
            }
            log.error("{} was being written by others the whole time; this write was given up after {} tries.",
                    address.key(), ATTEMPTS);
            return false;
        }
    }

    private boolean updateDocument(Consumer<YamlConfiguration> change) {
        bringInFile();
        synchronized (lock) {
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                Documents.Read now = documents.read(address);
                if (now instanceof Documents.Failed failed) {
                    log.error("{} could not be read ({}), so this change was not written to it.", address.key(),
                            failed.why());
                    return false;
                }
                YamlConfiguration current;
                long version = 0;
                if (now instanceof Documents.Found found) {
                    Optional<YamlConfiguration> parsed = parse(found.body());
                    if (parsed.isEmpty()) {
                        log.error("{} is not valid YAML, so this change was not written to it. Fix it (export, edit, "
                                + "import) and the next change will be kept.", address.key());
                        return false;
                    }
                    current = parsed.get();
                    version = found.version();
                } else {
                    current = new YamlConfiguration();
                }
                String body;
                try {
                    change.accept(current);
                    body = current.saveToString();
                } catch (RuntimeException failure) {
                    log.error(failure, "Could not build the contents of {}; it is unchanged.", address.key());
                    return false;
                }
                long expected = version;
                boolean written = asThisStore(() -> expected == 0
                        ? documents.insertIfAbsent(address, body)
                        : documents.compareAndSet(address, expected, body));
                if (written) {
                    remember(body, version == 0 ? 1 : version + 1);
                    return true;
                }
                if (!documents.isUsable()) {
                    return false;
                }
            }
            log.error("{} was being written by others the whole time; this change was given up after {} tries.",
                    address.key(), ATTEMPTS);
            return false;
        }
    }

    private Optional<Path> quarantineDocument() {
        Documents.Read read = documents.read(address);
        if (!(read instanceof Documents.Found found)) {
            return Optional.empty();
        }
        String keptName = address.name() + ".broken-" + STAMP.format(LocalDateTime.now());
        Documents.Address kept = new Documents.Address(address.scope(), address.owner(), keptName);
        if (!documents.insertIfAbsent(kept, found.body()) || !documents.delete(address)) {
            log.error("Could not set aside the unreadable document {}", address.key());
            return Optional.empty();
        }
        synchronized (this) {
            unreadable = false;
        }
        baseKnown = false;
        log.warn("{} could not be read and has been kept as {} rather than written over.", address.key(), keptName);
        return Optional.of(file.resolveSibling(file.getFileName() + keptName.substring(address.name().length())));
    }

    /**
     * The first time this document is used in a run: a file left from 1.x is brought into the database and
     * renamed {@code *.imported-2.0.0} — never deleted. When the document already has content (another
     * server of the network brought its own file in first), what is there wins and the file adds the rest.
     */
    private void bringInFile() {
        if (file == null || !Files.isRegularFile(file) || !documents.firstTimeFor(address)) {
            return;
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException unreadableFile) {
            log.error(unreadableFile, "{} could not be read, so it was not moved into the database.", file);
            return;
        }
        Optional<YamlConfiguration> fromFile = parse(text);
        if (fromFile.isEmpty()) {
            log.error("{} is not valid YAML, so it was not moved into the database. Fix it and restart.", file);
            return;
        }
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Documents.Read now = documents.read(address);
            if (now instanceof Documents.Failed failed) {
                log.error("{} could not be moved into the database ({}); it stays a file until it can.", file,
                        failed.why());
                return;
            }
            if (now instanceof Documents.Absent) {
                if (documents.insertIfAbsent(address, text)) {
                    retire(file, "moved into the database");
                    return;
                }
                continue;
            }
            Documents.Found found = (Documents.Found) now;
            Optional<YamlConfiguration> stored = parse(found.body());
            if (stored.isEmpty()) {
                log.error("{} in the database is not valid YAML, so {} was not merged into it.", address.key(), file);
                return;
            }
            YamlMerge.Result merged = YamlMerge.importInto(fromFile.get(), stored.get());
            String body = merged.yaml().saveToString();
            if (body.equals(stored.get().saveToString()) || documents.compareAndSet(address, found.version(), body)) {
                if (!merged.conflicts().isEmpty()) {
                    log.warn("{}: kept what the database had at {} over this server's file", address.key(),
                            String.join(", ", merged.conflicts()));
                }
                retire(file, "merged into the database");
                return;
            }
        }
        log.error("{} could not be merged into the database; it stays a file and is tried again next start.", file);
    }

    private static void retire(Path file, String what) {
        Path kept = file.resolveSibling(file.getFileName() + IMPORTED);
        if (Files.exists(kept)) {
            kept = file.resolveSibling(file.getFileName() + IMPORTED + "-" + STAMP.format(LocalDateTime.now()));
        }
        try {
            Files.move(file, kept);
            log.info("{} was {}; the file is kept as {}.", file.getFileName(), what, kept.getFileName());
        } catch (IOException failure) {
            log.warn("{} was {}, but could not be renamed ({}); it will be merged again next start, which "
                    + "changes nothing.", file, what, failure.getMessage());
        }
    }

    private boolean asThisStore(java.util.function.BooleanSupplier write) {
        YamlStore before = WRITING.get();
        WRITING.set(this);
        try {
            return write.getAsBoolean();
        } finally {
            WRITING.set(before);
        }
    }

    private void remember(String body, long version) {
        synchronized (lock) {
            baseBody = body;
            baseVersion = version;
            baseKnown = true;
        }
    }

    private static Optional<YamlConfiguration> parse(String body) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(body == null ? "" : body);
            return Optional.of(yaml);
        } catch (InvalidConfigurationException | RuntimeException broken) {
            return Optional.empty();
        }
    }
}
