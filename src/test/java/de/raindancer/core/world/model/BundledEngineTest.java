package de.raindancer.core.world.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BundledEngineTest {

    @Test
    @DisplayName("Core carries BetterModel's jar and its MIT licence")
    void bundled() throws IOException {
        try (InputStream jar = BundledEngine.class.getResourceAsStream(BundledEngine.JAR)) {
            assertThat(jar).isNotNull();
            assertThat(jar.readNBytes(2)).containsExactly('P', 'K');
        }
        try (InputStream licence = BundledEngine.class.getResourceAsStream(BundledEngine.LICENCE)) {
            assertThat(licence).isNotNull();
            assertThat(new String(licence.readAllBytes())).contains("MIT License").contains("toxicity188");
        }
        assertThat(BundledEngine.JAR).contains(BundledEngine.VERSION);
    }

    @Test
    @DisplayName("no BetterModel in plugins/: install it; an older one: replace it; the same or newer: leave it")
    void plans() {
        assertThat(BundledEngine.plan(List.of("RainsCore-2.40.0.jar", "LuckPerms.jar"), "3.6.0").action())
                .isEqualTo(BundledEngine.Action.INSTALL);
        BundledEngine.Plan older = BundledEngine.plan(List.of("bettermodel-3.5.1-paper.jar"), "3.6.0");
        assertThat(older.action()).isEqualTo(BundledEngine.Action.UPDATE);
        assertThat(older.present()).contains("bettermodel-3.5.1-paper.jar");
        assertThat(BundledEngine.plan(List.of("BetterModel-3.6.0.jar"), "3.6.0").action())
                .isEqualTo(BundledEngine.Action.NONE);
        assertThat(BundledEngine.plan(List.of("bettermodel-3.7.0-SNAPSHOT-540-paper.jar"), "3.6.0").action())
                .as("an owner's newer snapshot is theirs").isEqualTo(BundledEngine.Action.NONE);
        assertThat(BundledEngine.plan(List.of("bettermodel-3.10.0-paper.jar"), "3.6.0").action())
                .as("numbers compare as numbers").isEqualTo(BundledEngine.Action.NONE);
    }

    @Test
    @DisplayName("installing writes the jar into plugins/; an older one is moved aside, not deleted")
    void installs(@TempDir Path plugins) throws IOException {
        Path aside = plugins.resolve("RainsCore").resolve("replaced");
        assertThat(BundledEngine.install(plugins, aside)).isEqualTo(BundledEngine.Action.INSTALL);
        assertThat(plugins.resolve(BundledEngine.FILE_NAME)).exists();
        assertThat(BundledEngine.install(plugins, aside)).as("once is enough").isEqualTo(BundledEngine.Action.NONE);

        Files.delete(plugins.resolve(BundledEngine.FILE_NAME));
        Files.writeString(plugins.resolve("bettermodel-3.5.1-paper.jar"), "old");
        assertThat(BundledEngine.install(plugins, aside)).isEqualTo(BundledEngine.Action.UPDATE);
        assertThat(plugins.resolve("bettermodel-3.5.1-paper.jar")).doesNotExist();
        assertThat(aside.resolve("bettermodel-3.5.1-paper.jar")).hasContent("old");
        assertThat(plugins.resolve(BundledEngine.FILE_NAME)).exists();
    }
}
