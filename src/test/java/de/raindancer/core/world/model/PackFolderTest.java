package de.raindancer.core.world.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackFolderTest {

    @Test
    @DisplayName("the engine's assets become a folder Core can contribute; what the last build had and this one has not goes")
    void writes(@TempDir Path folder) throws IOException {
        Files.createDirectories(folder.resolve("assets/bettermodel/models"));
        Files.writeString(folder.resolve("assets/bettermodel/models/gone.json"), "{}");
        Map<String, byte[]> assets = new LinkedHashMap<>();
        assets.put("pack.mcmeta", "{\"pack\":{}}".getBytes());
        assets.put("assets/bettermodel/models/item/a.json", "{\"a\":1}".getBytes());
        assets.put("assets/bettermodel/textures/item/a.png", new byte[]{1, 2, 3});

        assertThat(PackFolder.write(assets, folder)).isEqualTo(3);
        assertThat(folder.resolve("assets/bettermodel/models/item/a.json")).hasContent("{\"a\":1}");
        assertThat(folder.resolve("assets/bettermodel/models/gone.json")).doesNotExist();
    }

    @Test
    @DisplayName("a path that would leave the folder is refused")
    void staysInside(@TempDir Path folder) {
        assertThatThrownBy(() -> PackFolder.write(Map.of("../escape.txt", new byte[0]), folder))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("the engine's zip on disk reads the same way, for a build that finished before Core was listening")
    void readsZip(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("build.zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("assets/bettermodel/models/item/b.json"));
            z.write("{\"b\":2}".getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("assets/bettermodel/"));
            z.closeEntry();
        }
        Map<String, byte[]> read = PackFolder.readZip(zip);
        assertThat(read).containsOnlyKeys("assets/bettermodel/models/item/b.json");
        assertThat(PackFolder.write(read, dir.resolve("out"))).isEqualTo(1);
    }
}
