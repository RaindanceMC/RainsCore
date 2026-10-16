package de.raindancer.core.world.build;

import de.raindancer.core.world.safety.Spot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PasteJobTest {

    private static BatchBuilder.Placement at(int x, int y, int z) {
        return new BatchBuilder.Placement(new Spot("arena", x, y, z), "minecraft:stone");
    }

    @Test
    @DisplayName("placements are grouped by the chunk they fall in, negative coordinates included")
    void byChunk() {
        Map<Long, List<BatchBuilder.Placement>> chunks = PasteJob.byChunk(List.of(
                at(0, 64, 0), at(15, 64, 15), at(16, 64, 0), at(-1, 64, -1), at(-16, 64, -16), at(-17, 64, 0)));

        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(PasteJob.key(0, 0))).hasSize(2);
        assertThat(chunks.get(PasteJob.key(1, 0))).hasSize(1);
        assertThat(chunks.get(PasteJob.key(-1, -1))).hasSize(2);
        assertThat(chunks.get(PasteJob.key(-2, 0))).hasSize(1);
    }

    @Test
    @DisplayName("inside a chunk the order is kept, so what stands on something still goes on after it")
    void orderKept() {
        Map<Long, List<BatchBuilder.Placement>> chunks = PasteJob.byChunk(List.of(
                at(1, 64, 1), at(1, 65, 1), at(1, 66, 1)));

        assertThat(chunks.get(PasteJob.key(0, 0))).extracting(placement -> placement.spot().y())
                .containsExactly(64, 65, 66);
    }

    @Test
    @DisplayName("a chunk key gives back the chunk it was made from")
    void keyRoundTrip() {
        for (int[] chunk : new int[][]{{0, 0}, {-1, 5}, {30000, -30000}, {-1, -1}}) {
            long key = PasteJob.key(chunk[0], chunk[1]);
            assertThat(PasteJob.chunkX(key)).isEqualTo(chunk[0]);
            assertThat(PasteJob.chunkZ(key)).isEqualTo(chunk[1]);
        }
    }
}
