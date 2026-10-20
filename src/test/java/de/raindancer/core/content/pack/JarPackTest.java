package de.raindancer.core.content.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("A plugin's pack files, copied out of its jar")
class JarPackTest {

    private static final String ROOT = "/de/raindancer/core/content/pack/jarpack/";

    @TempDir
    Path folder;

    @Test
    @DisplayName("every listed file lands under the folder, in its own place; copying again replaces it")
    void copies() throws IOException {
        List<String> files = List.of("pack.mcmeta", "assets/demo/items/thing.json");
        JarPack.copy(JarPackTest.class, ROOT, files, folder);
        JarPack.copy(JarPackTest.class, ROOT, files, folder);
        assertThat(folder.resolve("pack.mcmeta")).exists();
        assertThat(Files.readString(folder.resolve("assets/demo/items/thing.json"))).contains("demo:item/thing");
    }

    @Test
    @DisplayName("a listed file missing from the jar is said by name, not copied as nothing")
    void missing() {
        assertThatThrownBy(() -> JarPack.copy(JarPackTest.class, ROOT, List.of("assets/demo/items/gone.json"), folder))
                .isInstanceOf(IOException.class).hasMessageContaining("assets/demo/items/gone.json");
    }
}
