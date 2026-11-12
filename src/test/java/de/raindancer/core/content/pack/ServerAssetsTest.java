package de.raindancer.core.content.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A server owner's own files in the one pack, on top of every plugin's. */
class ServerAssetsTest {

    @TempDir
    Path folder;

    private ResourcePacks packs() {
        return new ResourcePacks(folder.resolve("work"), new PackSink() {
            @Override
            public void send(UUID player, List<PackOffer> offers) {
            }

            @Override
            public void clear(UUID player) {
            }
        });
    }

    @Test
    void anEmptyFolderIsMadeAndOffersNothing() throws Exception {
        ResourcePacks packs = packs();
        assertThat(ServerAssets.offer(folder, packs)).isFalse();
        assertThat(Files.isDirectory(folder.resolve("pack/server/assets"))).isTrue();
        assertThat(Files.isRegularFile(folder.resolve("pack/server/README.txt"))).isTrue();
        assertThat(Files.readString(folder.resolve("pack/server/pack.mcmeta"))).contains("min_format");
        assertThat(packs.contributions()).isEmpty();
    }

    @Test
    void filesInItAreOfferedAndWinEveryConflict() throws Exception {
        Path sprite = folder.resolve("pack/server/assets/minecraft/textures/gui/sprites/dialog/warning_button.png");
        Files.createDirectories(sprite.getParent());
        Files.write(sprite, new byte[]{1, 2, 3});
        ResourcePacks packs = packs();

        assertThat(ServerAssets.offer(folder, packs)).isTrue();
        assertThat(packs.contributions()).singleElement().satisfies(offered -> {
            assertThat(offered.id()).isEqualTo("server:assets");
            assertThat(offered.priority()).isEqualTo(ServerAssets.PRIORITY);
            assertThat(offered.source()).isEqualTo(folder.resolve("pack/server"));
        });
        assertThat(ServerAssets.PRIORITY).isGreaterThan(1000);
    }
}
