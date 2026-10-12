package de.raindancer.core.world.build;

import de.raindancer.core.data.nbt.Nbt;
import de.raindancer.core.data.nbt.Tag;
import de.raindancer.core.world.safety.Spot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading Sponge schematics ({@code .schem}, versions 2 and 3) — what WorldEdit writes, and what map sites hand out.
 *
 * <p>The files are built here rather than checked in: a map somebody downloaded is not ours to ship, and a fixture
 * written by hand can put a block exactly where a test wants to look.
 */
class SchematicTest {

    /** Varints, the way the format packs palette indices: seven bits at a time, low first. */
    private static byte[] varints(int... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int value : values) {
            while ((value & ~0x7F) != 0) {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write(value);
        }
        return out.toByteArray();
    }

    private static Tag.Compound palette(String... states) {
        Map<String, Tag> palette = new LinkedHashMap<>();
        for (int index = 0; index < states.length; index++) {
            palette.put(states[index], new Tag.Int(index));
        }
        return new Tag.Compound(palette);
    }

    private static Tag.Compound version2(int width, int height, int length, int dataVersion, Tag.Compound palette,
                                         byte[] data) {
        Map<String, Tag> root = new LinkedHashMap<>();
        root.put("Version", new Tag.Int(2));
        root.put("DataVersion", new Tag.Int(dataVersion));
        root.put("Width", new Tag.Short((short) width));
        root.put("Height", new Tag.Short((short) height));
        root.put("Length", new Tag.Short((short) length));
        root.put("PaletteMax", new Tag.Int(palette.size()));
        root.put("Palette", palette);
        root.put("BlockData", new Tag.ByteArray(data));
        root.put("Offset", new Tag.IntArray(new int[]{0, 0, 0}));
        return new Tag.Compound(root);
    }

    private static Tag.Compound version3(int width, int height, int length, int dataVersion, Tag.Compound palette,
                                         byte[] data) {
        Map<String, Tag> blocks = new LinkedHashMap<>();
        blocks.put("Palette", palette);
        blocks.put("Data", new Tag.ByteArray(data));
        blocks.put("BlockEntities", Tag.List_.empty());
        Map<String, Tag> schematic = new LinkedHashMap<>();
        schematic.put("Version", new Tag.Int(3));
        schematic.put("DataVersion", new Tag.Int(dataVersion));
        schematic.put("Width", new Tag.Short((short) width));
        schematic.put("Height", new Tag.Short((short) height));
        schematic.put("Length", new Tag.Short((short) length));
        schematic.put("Blocks", new Tag.Compound(blocks));
        return new Tag.Compound(Map.of("Schematic", new Tag.Compound(schematic)));
    }

    private static Schematic roundTrip(Tag.Compound root) throws IOException {
        return Schematic.read(new ByteArrayInputStream(Nbt.writeCompressed(root)));
    }

    @Test
    @DisplayName("a version 2 file reads back block for block, x fastest, then z, then y")
    void readsVersionTwo() throws IOException {
        // 2 wide, 2 high, 2 long: index = x + z*width + y*width*length
        Schematic schematic = roundTrip(version2(2, 2, 2, 3700,
                palette("minecraft:air", "minecraft:stone", "minecraft:oak_stairs[facing=east,half=top]"),
                varints(1, 0, 0, 0, 0, 0, 0, 2)));

        assertThat(schematic.width()).isEqualTo(2);
        assertThat(schematic.height()).isEqualTo(2);
        assertThat(schematic.length()).isEqualTo(2);
        assertThat(schematic.dataVersion()).isEqualTo(3700);
        assertThat(schematic.stateAt(0, 0, 0)).isEqualTo("minecraft:stone");
        assertThat(schematic.stateAt(1, 1, 1)).isEqualTo("minecraft:oak_stairs[facing=east,half=top]");
        assertThat(schematic.stateAt(1, 0, 0)).isEqualTo("minecraft:air");
    }

    @Test
    @DisplayName("a version 3 file — the blocks one level down, under Schematic and Blocks — reads the same")
    void readsVersionThree() throws IOException {
        Schematic schematic = roundTrip(version3(3, 1, 1, 4000,
                palette("minecraft:air", "minecraft:dirt"), varints(1, 0, 1)));

        assertThat(schematic.stateAt(0, 0, 0)).isEqualTo("minecraft:dirt");
        assertThat(schematic.stateAt(1, 0, 0)).isEqualTo("minecraft:air");
        assertThat(schematic.stateAt(2, 0, 0)).isEqualTo("minecraft:dirt");
    }

    @Test
    @DisplayName("palette indices past 127 take two bytes and still land on the right block")
    void readsWideVarints() throws IOException {
        String[] states = new String[300];
        for (int index = 0; index < states.length; index++) {
            states[index] = "minecraft:block_" + index;
        }
        Schematic schematic = roundTrip(version2(3, 1, 1, 3700, palette(states), varints(5, 200, 299)));

        assertThat(schematic.stateAt(0, 0, 0)).isEqualTo("minecraft:block_5");
        assertThat(schematic.stateAt(1, 0, 0)).isEqualTo("minecraft:block_200");
        assertThat(schematic.stateAt(2, 0, 0)).isEqualTo("minecraft:block_299");
    }

    @Test
    @DisplayName("placements leave out air, start at the origin, and go up from the bottom layer")
    void placementsBottomUp() throws IOException {
        Schematic schematic = roundTrip(version2(2, 2, 1, 3700,
                palette("minecraft:air", "minecraft:stone", "minecraft:torch"), varints(1, 1, 2, 0)));

        List<BatchBuilder.Placement> placements = schematic.placements(new Spot("arena", 100, 64, -50));

        assertThat(placements).extracting(BatchBuilder.Placement::spot).containsExactly(
                new Spot("arena", 100, 64, -50), new Spot("arena", 101, 64, -50), new Spot("arena", 100, 65, -50));
        assertThat(placements).extracting(BatchBuilder.Placement::material)
                .containsExactly("minecraft:stone", "minecraft:stone", "minecraft:torch");
    }

    @Test
    @DisplayName("a pasted schematic actually builds, block for block, through the same queue as everything else")
    void buildsThroughTheBatchBuilder() throws IOException {
        Schematic schematic = roundTrip(version2(2, 1, 1, 3700,
                palette("minecraft:air", "minecraft:stone", "minecraft:dirt"), varints(1, 2)));
        MapGround ground = new MapGround();

        BatchBuilder builder = new BatchBuilder(ground, schematic.placements(new Spot("world", 0, 64, 0)));
        builder.advance(10);

        assertThat(builder.isDone()).isTrue();
        assertThat(ground.materialAt(new Spot("world", 0, 64, 0))).isEqualTo("minecraft:stone");
        assertThat(ground.materialAt(new Spot("world", 1, 64, 0))).isEqualTo("minecraft:dirt");
    }

    @Test
    @DisplayName("blocks Mojang has renamed since the file was made come out under today's name")
    void renamedBlocksAreUpgraded() throws IOException {
        Schematic schematic = roundTrip(version2(4, 1, 1, 2730,
                palette("minecraft:grass", "minecraft:chain[axis=y,waterlogged=false]", "minecraft:grass_path",
                        "minecraft:tall_grass[half=upper]"),
                varints(0, 1, 2, 3)));

        assertThat(schematic.stateAt(0, 0, 0)).isEqualTo("minecraft:short_grass");
        assertThat(schematic.stateAt(1, 0, 0)).isEqualTo("minecraft:iron_chain[axis=y,waterlogged=false]");
        assertThat(schematic.stateAt(2, 0, 0)).isEqualTo("minecraft:dirt_path");
        assertThat(schematic.stateAt(3, 0, 0))
                .as("only the whole name is renamed — tall_grass is not 'grass' with something in front")
                .isEqualTo("minecraft:tall_grass[half=upper]");
    }

    @Test
    @DisplayName("blocks from a mod are named, so a paste can say what it left out, and are not placed")
    void foreignBlocksAreReported() throws IOException {
        Schematic schematic = roundTrip(version2(3, 1, 1, 3465,
                palette("minecraft:stone", "create:cut_deepslate_brick_stairs[facing=north]", "minecraft:air"),
                varints(0, 1, 1)));

        assertThat(schematic.foreign()).containsExactly("create:cut_deepslate_brick_stairs");
        assertThat(schematic.placements(new Spot("w", 0, 0, 0)))
                .extracting(BatchBuilder.Placement::material).containsExactly("minecraft:stone");
    }

    @Test
    @DisplayName("a file with fewer blocks than its size promises is refused, not read past its end")
    void shortDataIsRefused() {
        assertThatThrownBy(() -> roundTrip(version2(2, 2, 2, 3700, palette("minecraft:stone"), varints(0, 0, 0))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("8");
    }

    @Test
    @DisplayName("an index pointing past the palette is refused rather than guessed")
    void indexOutsideThePaletteIsRefused() {
        assertThatThrownBy(() -> roundTrip(version2(1, 1, 1, 3700, palette("minecraft:stone"), varints(4))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("palette");
    }

    @Test
    @DisplayName("a size nobody could build is refused before anything is reserved for it")
    void absurdSizesAreRefused() {
        assertThatThrownBy(() -> roundTrip(version2(30000, 30000, 30000, 3700, palette("minecraft:stone"),
                varints(0))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("too large");
    }

    @Test
    @DisplayName("something that is not a schematic at all says so")
    void notASchematic() {
        assertThatThrownBy(() -> roundTrip(new Tag.Compound(Map.of("Data", new Tag.Int(1)))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("schematic");
    }
}
