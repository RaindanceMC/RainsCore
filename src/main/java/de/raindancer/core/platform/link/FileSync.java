package de.raindancer.core.platform.link;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Files kept on one server of the link and mirrored on the one that follows it — a chat config, the ranks, the prefix.
 * A plugin {@link #register registers} the files it wants in step, by path under the plugins folder, and what to do
 * once one has changed. Only registered files are ever served, so the link can never be asked for anything else.
 *
 * <p>The follower copies a file when the other side's version differs from the last one it applied — not when its own
 * differs, because a plugin rewriting its file with defaults would otherwise be undone and reloaded every round.
 */
public final class FileSync {

    public static final String TOPIC = "core.file";
    private static final String MISSING = "__missing";
    private static final long MOST_BYTES = 512 * 1024;

    private static final LogChannel log = Log.of("link");

    private final Path plugins;
    private final ServerLink followed;
    private final Map<String, Runnable> registered = new ConcurrentHashMap<>();
    private final Map<String, String> applied = new ConcurrentHashMap<>();
    /** What was last said about a file that did not come, so the same trouble is logged once, not every round. */
    private final Map<String, String> trouble = new ConcurrentHashMap<>();

    /**
     * @param serving  every link on which this server answers for its files
     * @param followed the link whose other side this server mirrors; null when it mirrors nobody
     */
    public FileSync(Path plugins, Collection<ServerLink> serving, ServerLink followed) {
        this.plugins = plugins.toAbsolutePath().normalize();
        this.followed = followed;
        serving.forEach(end -> end.on(TOPIC, this::serve));
    }

    /** Keeps this file in step; {@code changed} runs after a newer version has been written here. */
    public void register(String relative, Runnable changed) {
        if (resolve(relative) != null) {
            registered.put(relative, changed);
        }
    }

    public boolean isRegistered(String relative) {
        return registered.containsKey(relative);
    }

    /** Pulls every registered file. Completes when all have answered or failed. */
    public CompletableFuture<Void> pullAll() {
        return CompletableFuture.allOf(registered.keySet().stream().map(this::pull)
                .toArray(CompletableFuture[]::new));
    }

    /** @return whether a newer version was written here */
    public CompletableFuture<Boolean> pull(String relative) {
        Path target = resolve(relative);
        if (followed == null || target == null || !registered.containsKey(relative)) {
            return CompletableFuture.completedFuture(false);
        }
        return followed.request(TOPIC, Map.of("path", relative), Duration.ofSeconds(4)).thenApply(answer -> {
            String content = answer.get("content");
            String sha = answer.get("sha");
            if (answer.containsKey(MISSING) || content == null || sha == null) {
                note(relative, answer.isEmpty() ? "the other server has nothing registered for it (empty answer)" : "the other server does not have it registered or readable");
                return false;
            }
            trouble.remove(relative);
            if (sha.equals(applied.get(relative))) {
                return false;
            }
            try {
                byte[] bytes = Base64.getDecoder().decode(content);
                Files.createDirectories(target.getParent());
                Path temp = target.resolveSibling(target.getFileName() + ".sync");
                Files.write(temp, bytes);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                applied.put(relative, sha);
            } catch (IOException | IllegalArgumentException unwritable) {
                log.warn("The synced file {} could not be written: {}", relative, unwritable.getMessage());
                return false;
            }
            log.info("Synced {} from the other server.", relative);
            registered.get(relative).run();
            return true;
        }).exceptionally(failure -> {
            note(relative, "no answer (" + (failure.getCause() == null ? failure : failure.getCause()).getClass().getSimpleName() + ")");
            return false;
        });
    }

    private void note(String relative, String why) {
        if (!why.equals(trouble.put(relative, why))) {
            log.warn("Synced file {}: {}.", relative, why);
        }
    }

    private Map<String, String> serve(Map<String, String> data) {
        String relative = data.getOrDefault("path", "");
        Path file = registered.containsKey(relative) ? resolve(relative) : null;
        try {
            if (file == null || !Files.isRegularFile(file) || Files.size(file) > MOST_BYTES) {
                return Map.of(MISSING, "true");
            }
            byte[] bytes = Files.readAllBytes(file);
            return Map.of("content", Base64.getEncoder().encodeToString(bytes), "sha", sha(bytes));
        } catch (IOException unreadable) {
            return Map.of(MISSING, "true");
        }
    }

    /** The file under the plugins folder, or null for a path that leaves it. */
    private Path resolve(String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        Path file = plugins.resolve(relative).normalize();
        return file.startsWith(plugins) && !file.equals(plugins) ? file : null;
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Stops answering — what the plugin does when it shuts down. */
    public void stop(Collection<ServerLink> serving) {
        serving.forEach(end -> end.off(TOPIC));
    }
}
