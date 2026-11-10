package de.raindancer.core.world.manage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorldFoldersTest {

    @TempDir
    Path container;

    @Test
    void aWorldMadeAtRuntimeLivesUnderTheMainLevelsDimensions() throws IOException {
        Files.createDirectories(container.resolve("world/dimensions/minecraft/tutorial"));
        assertThat(WorldFolders.exists(container, "world", "tutorial")).isTrue();
    }

    @Test
    void theOldLayoutBesideTheMainLevelCountsToo() throws IOException {
        Files.createDirectories(container.resolve("tutorial"));
        assertThat(WorldFolders.exists(container, "world", "tutorial")).isTrue();
    }

    @Test
    void nothingThereIsNothing() {
        assertThat(WorldFolders.exists(container, "world", "tutorial")).isFalse();
        assertThat(WorldFolders.exists(container, "world", "")).isFalse();
    }
}
