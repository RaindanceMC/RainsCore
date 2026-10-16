package de.raindancer.core.world.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SchematicWorldTest {

    @Test
    void theBuildIsCentredOnTheWorldsMiddleAndTheSpawnFollowsIt() {
        // the floating island: 68 wide, 67 long, spawn at its middle (32.5 20 32.5)
        SchematicWorld.Landing absolute = SchematicWorld.absolute(68, 67,
                new SchematicWorld.Landing(32.5, 20, 32.5, 0, 0));
        assertEquals(new SchematicWorld.Landing(-1.5, SchematicWorld.BASE_Y + 20, -0.5, 0, 0), absolute);
    }

    @Test
    void aLandingIsWrittenAsOneLine() {
        assertEquals(java.util.Optional.of(new SchematicWorld.Landing(32.5, 20, 32.5, 90, 0)),
                SchematicWorld.Landing.parse("32.5 20 32.5 90 0"));
        assertEquals(java.util.Optional.of(new SchematicWorld.Landing(1, 2, 3, 0, 0)),
                SchematicWorld.Landing.parse("1 2 3"));
        assertEquals(java.util.Optional.empty(), SchematicWorld.Landing.parse("1 2"));
    }

    @Test
    void anotherFileIsAnotherVersion(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("course.schem");
        Files.write(file, new byte[]{1, 2, 3});
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000));
        long first = SchematicWorld.version(file);
        assertEquals(first, SchematicWorld.version(file));
        Files.write(file, new byte[]{1, 2, 3, 4});
        Files.setLastModifiedTime(file, FileTime.fromMillis(2_000));
        assertNotEquals(first, SchematicWorld.version(file));
        assertEquals(-2, SchematicWorld.version(folder.resolve("missing.schem")));
    }
}
