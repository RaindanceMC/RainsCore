package de.raindancer.core.world.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchematicCaptureTest {

    @Test
    void theChunksOfABoxAreEveryChunkItTouches() {
        // x 1024..1311 (288 wide), z 0..185: chunks 64..81 by 0..11
        List<long[]> chunks = SchematicCapture.chunksOf(1024, 0, 288, 186);
        assertEquals(18 * 12, chunks.size());
        assertEquals(64, chunks.getFirst()[0]);
        assertEquals(0, chunks.getFirst()[1]);
        assertEquals(81, chunks.getLast()[0]);
        assertEquals(11, chunks.getLast()[1]);
    }

    @Test
    void aBoxAcrossTheOriginCountsNegativeChunks() {
        List<long[]> chunks = SchematicCapture.chunksOf(-20, -1, 40, 2);
        assertEquals(List.of(-2L, -1L, 0L, 1L), chunks.stream().map(c -> c[0]).distinct().toList());
        assertEquals(List.of(-1L, 0L), chunks.stream().map(c -> c[1]).distinct().toList());
    }
}
