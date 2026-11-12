package de.raindancer.core.content.pack;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * The server's own files in the pack: whatever an owner puts under {@code plugins/RainsCore/pack/server/assets/} —
 * their logo over a game sprite, a sound, a font — goes into the one pack every player gets, above every plugin's,
 * so the owner's choice wins any conflict. Read at startup; a change needs a restart.
 */
public final class ServerAssets {

    /** Above anything a plugin offers. */
    public static final int PRIORITY = 10_000;

    private static final LogChannel log = Log.of("pack");

    private static final String README = """
            Files here go into the resource pack every player gets, above every plugin's, at the next restart.
            Lay them out like a resource pack: assets/<namespace>/..., e.g.
              assets/minecraft/textures/gui/sprites/dialog/warning_button.png
            replaces the small icon at the top of the game's dialog windows.
            """;

    private ServerAssets() {
    }

    /** @return whether anything was offered */
    public static boolean offer(Path dataFolder, ResourcePacks packs) {
        Path root = dataFolder.resolve("pack").resolve("server");
        Path assets = root.resolve("assets");
        try {
            Files.createDirectories(assets);
            // The builder refuses a pack without one. A wide range: the merger keeps the narrowest of all packs anyway.
            Path meta = root.resolve("pack.mcmeta");
            if (!Files.exists(meta)) {
                Files.writeString(meta, "{\"pack\":{\"description\":\"This server's own files\","
                        + "\"min_format\":1,\"max_format\":999}}");
            }
            Path readme = root.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, README);
            }
            boolean any;
            try (Stream<Path> files = Files.walk(assets)) {
                any = files.anyMatch(Files::isRegularFile);
            }
            if (!any) {
                return false;
            }
        } catch (IOException failure) {
            log.warn("The server's own pack folder {} could not be read: {}", root, failure.getMessage());
            return false;
        }
        boolean offered = packs.contribute(PackContribution.of("Server", "assets", root).priority(PRIORITY)
                .describedAs("This server's own files (plugins/RainsCore/pack/server)"));
        log.info("The server's own files from {} are in the pack.", root);
        return offered;
    }
}
