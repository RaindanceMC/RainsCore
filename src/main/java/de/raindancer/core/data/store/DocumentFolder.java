package de.raindancer.core.data.store;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * A folder of documents — one per claim, one per mannequin, one per player — for a store that used to list a
 * folder of files. With Core running the entries are documents; the 1.x files still lying in the folder are
 * listed too until each is opened (and so brought in), so nothing goes missing while a server moves over.
 * Without Core it is the folder of files it always was.
 */
public final class DocumentFolder {

    private static final LogChannel log = Log.of("store");

    private final Path folder;
    private final Documents documents;
    private final Documents.Scope scope;

    private DocumentFolder(Path folder, Documents documents, Documents.Scope scope) {
        this.folder = folder.toAbsolutePath().normalize();
        this.documents = documents;
        this.scope = scope == null ? Documents.Scope.SERVER : scope;
    }

    /** This server's folder of documents (as its files were). */
    public static DocumentFolder of(Path folder) {
        return on(folder, Documents.installed().orElse(null), Documents.Scope.SERVER);
    }

    /** The network's folder: the same entries on every server. */
    public static DocumentFolder network(Path folder) {
        return on(folder, Documents.installed().orElse(null), Documents.Scope.NETWORK);
    }

    public static DocumentFolder on(Path folder, Documents documents, Documents.Scope scope) {
        return new DocumentFolder(folder, documents, scope);
    }

    /** The entries ending in {@code suffix} (".yml"), directly in this folder, by name, sorted. */
    public List<String> names(String suffix) {
        TreeSet<String> names = new TreeSet<>();
        if (documents != null) {
            documents.addressOf(folder.resolve("x"), scope).ifPresent(probe -> {
                String prefix = probe.name().substring(0, probe.name().length() - 1);
                for (String name : documents.list(probe.scope(), probe.owner(), prefix)) {
                    String rest = name.substring(prefix.length());
                    if (!rest.contains("/") && rest.endsWith(suffix)) {
                        names.add(rest);
                    }
                }
            });
        }
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(Files::isRegularFile).map(file -> file.getFileName().toString())
                        .filter(name -> name.endsWith(suffix)).forEach(names::add);
            } catch (IOException unreadable) {
                log.warn("{} could not be listed: {}", folder, unreadable.getMessage());
            }
        }
        return List.copyOf(names);
    }

    /** The store of one entry; opening it brings a 1.x file of that name in. */
    public YamlStore store(String name) {
        return documents == null ? new YamlStore(inside(name)) : new YamlStore(inside(name), documents, scope);
    }

    /** Removes one entry — its document, and a 1.x file of that name if one is still there. */
    public boolean delete(String name) {
        Path file;
        try {
            file = inside(name);
        } catch (IllegalArgumentException outside) {
            return false;
        }
        boolean gone = store(name).delete();
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            log.warn("{} could not be deleted: {}", file, failure.getMessage());
            return false;
        }
        return gone;
    }

    /**
     * One plain name, checked as a name rather than as a path: no separator of either kind (a document's name
     * turns '\\' into '/', a file's does not — the two must never disagree about which entry is meant), no
     * "." or "..", nothing unprintable.
     */
    private Path inside(String name) {
        boolean plain = name != null && !name.isBlank() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.chars().noneMatch(c -> c < 0x20 || c == 0x7f);
        if (!plain) {
            throw new IllegalArgumentException("Not an entry of " + folder + ": '" + name + "'");
        }
        return folder.resolve(name);
    }
}
