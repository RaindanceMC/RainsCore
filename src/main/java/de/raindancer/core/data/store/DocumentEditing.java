package de.raindancer.core.data.store;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Editing a document by hand, now that it is a row in the database rather than a file beside the plugin.
 *
 * <p>{@code /documents export RainsChat config.yml} writes the document to
 * {@code plugins/RainsCore/edit/<network|server>/RainsChat/config.yml}; the owner edits it with whatever
 * they like; {@code /documents import} reads every file in that folder back. A file that is not valid YAML
 * is refused and left where it is, with the reason. An imported file is moved to
 * {@code edit/applied/<time>/}, not deleted — it is the owner's edit. Every store watching the document
 * reloads, on this server and, for the network's documents, on every server.
 */
public final class DocumentEditing {

    private static final LogChannel log = Log.of("store");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    private static final String NETWORK = "network";
    private static final String SERVER = "server";

    /** One document: whose, under which name, and whether the network's or this server's. */
    public record Entry(String scope, String owner, String name) {
    }

    /** What an import did: the files applied, and those refused with why. */
    public record Imported(List<String> applied, Map<String, String> refused) {
    }

    private final Documents documents;
    private final Path editFolder;

    public DocumentEditing(Documents documents, Path editFolder) {
        this.documents = documents;
        this.editFolder = editFolder.toAbsolutePath().normalize();
    }

    public Path folder() {
        return editFolder;
    }

    /** The network's documents first, then this server's, each by plugin and name. */
    public List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        for (String owner : documents.owners(Documents.NETWORK_SCOPE)) {
            documents.list(Documents.NETWORK_SCOPE, owner, "").forEach(name -> entries.add(new Entry(NETWORK, owner, name)));
        }
        for (String owner : documents.owners(documents.serverScope())) {
            documents.list(documents.serverScope(), owner, "").forEach(name -> entries.add(new Entry(SERVER, owner, name)));
        }
        return entries;
    }

    /**
     * Writes one document to the edit folder — this server's if it has one by that name, else the network's.
     *
     * @return where it was written; empty when there is no such document, or the name leaves the folder
     */
    public Optional<Path> export(String owner, String name) {
        for (String scope : List.of(documents.serverScope(), Documents.NETWORK_SCOPE)) {
            Documents.Address address = new Documents.Address(scope, owner, name);
            if (!(documents.read(address) instanceof Documents.Found found)) {
                continue;
            }
            Optional<Path> target = inside(editFolder.resolve(tagOf(scope)).resolve(owner).resolve(name));
            if (target.isEmpty()) {
                return Optional.empty();
            }
            try {
                Files.createDirectories(target.get().getParent());
                Files.writeString(target.get(), found.body(), StandardCharsets.UTF_8);
                return target;
            } catch (IOException failure) {
                log.error(failure, "{} could not be written out for editing.", target.get());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Reads every edited file back into its document. */
    public Imported importAll() {
        List<String> applied = new ArrayList<>();
        Map<String, String> refused = new LinkedHashMap<>();
        if (!Files.isDirectory(editFolder)) {
            return new Imported(applied, refused);
        }
        Path appliedFolder = editFolder.resolve("applied").resolve(STAMP.format(LocalDateTime.now()));
        List<Path> files;
        try (Stream<Path> walk = Files.walk(editFolder)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(file -> !editFolder.relativize(file).startsWith("applied"))
                    .sorted().toList();
        } catch (IOException unreadable) {
            refused.put(editFolder.toString(), "the folder could not be read: " + unreadable.getMessage());
            return new Imported(applied, refused);
        }
        for (Path file : files) {
            Path relative = editFolder.relativize(file);
            String shown = relative.toString().replace('\\', '/');
            if (relative.getNameCount() < 3) {
                refused.put(shown, "not under network/<plugin>/ or server/<plugin>/");
                continue;
            }
            String tag = relative.getName(0).toString();
            String scope = switch (tag) {
                case NETWORK -> Documents.NETWORK_SCOPE;
                case SERVER -> documents.serverScope();
                default -> null;
            };
            if (scope == null) {
                refused.put(shown, "the first folder must be network or server");
                continue;
            }
            String owner = relative.getName(1).toString();
            String name = relative.subpath(2, relative.getNameCount()).toString().replace('\\', '/');
            String body;
            try {
                body = Files.readString(file, StandardCharsets.UTF_8);
                new YamlConfiguration().loadFromString(body);
            } catch (IOException | InvalidConfigurationException broken) {
                refused.put(shown, "not valid YAML: " + firstLine(broken.getMessage()));
                continue;
            }
            if (!write(new Documents.Address(scope, owner, name), body)) {
                refused.put(shown, "the database could not be written");
                continue;
            }
            try {
                Path kept = appliedFolder.resolve(relative);
                Files.createDirectories(kept.getParent());
                Files.move(file, kept);
            } catch (IOException failure) {
                log.warn("{} was imported but could not be moved to {} ({}); importing it again changes nothing.",
                        file, appliedFolder, failure.getMessage());
            }
            applied.add(shown);
        }
        return new Imported(applied, refused);
    }

    private boolean write(Documents.Address address, String body) {
        for (int attempt = 0; attempt < 8; attempt++) {
            Documents.Read now = documents.read(address);
            if (now instanceof Documents.Failed) {
                return false;
            }
            boolean written = now instanceof Documents.Found found
                    ? documents.compareAndSet(address, found.version(), body)
                    : documents.insertIfAbsent(address, body);
            if (written) {
                return true;
            }
        }
        return false;
    }

    /** The path, if it stays inside the edit folder — a name with ".." in it does not get to write elsewhere. */
    private Optional<Path> inside(Path candidate) {
        Path normal = candidate.toAbsolutePath().normalize();
        return normal.startsWith(editFolder) && !normal.equals(editFolder) ? Optional.of(normal) : Optional.empty();
    }

    private static String tagOf(String scope) {
        return Documents.NETWORK_SCOPE.equals(scope) ? NETWORK : SERVER;
    }

    private static String firstLine(String text) {
        return text == null ? "" : text.lines().findFirst().orElse("");
    }
}
