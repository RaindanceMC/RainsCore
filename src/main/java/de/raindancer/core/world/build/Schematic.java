package de.raindancer.core.world.build;

import de.raindancer.core.data.nbt.Nbt;
import de.raindancer.core.data.nbt.Tag;
import de.raindancer.core.world.safety.Spot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * A Sponge schematic ({@code .schem}, versions 2 and 3) — what WorldEdit writes and what map sites hand out — read
 * into block states and turned into placements for a {@link BatchBuilder}.
 *
 * <p>Blocks only. Block entities (a chest's items, a sign's text) and entities are not read: what this is for is
 * putting up a building, and a downloaded map's chest contents are not something a server should hand out.
 *
 * <p>No server needed: states stay strings until {@link Ground#set} resolves them, which is also where a state this
 * server does not know is refused rather than guessed.
 *
 * @param blocks palette index per position, {@code x + z * width + y * width * length}
 */
public record Schematic(int width, int height, int length, int dataVersion, List<String> palette, int[] blocks) {

    /** A hundred million blocks — larger than any build a server would paste, small enough to hold. */
    public static final long MOST_BLOCKS = 100_000_000L;

    private static final String AIR = "minecraft:air";

    /**
     * Blocks Mojang renamed, old name to new. Unconditional rather than keyed by data version: Mojang does not hand
     * a retired name to a different block, so an old name can only ever mean the block it used to.
     */
    private static final Map<String, String> RENAMED = Map.of(
            "minecraft:grass_path", "minecraft:dirt_path",
            "minecraft:grass", "minecraft:short_grass",
            "minecraft:chain", "minecraft:iron_chain");

    public Schematic {
        palette = List.copyOf(palette);
        blocks = blocks.clone();
    }

    @Override
    public int[] blocks() {
        return blocks.clone();
    }

    public static Schematic read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        } catch (IOException broken) {
            throw new IOException(file.getFileName() + ": " + broken.getMessage(), broken);
        }
    }

    public static Schematic read(InputStream in) throws IOException {
        return from(Nbt.readCompressed(in));
    }

    public static Schematic from(Tag.Compound root) throws IOException {
        // Version 3 nests everything under "Schematic"; version 2 is the root itself.
        Tag.Compound schematic = root.compound("Schematic").orElse(root);
        int version = schematic.intOr("Version", 0);
        Tag.Compound paletteTag;
        byte[] data;
        if (version >= 3) {
            Tag.Compound blocks = schematic.compound("Blocks")
                    .orElseThrow(() -> new IOException("a version 3 schematic without Blocks"));
            paletteTag = blocks.compound("Palette").orElseThrow(() -> new IOException("no block palette"));
            data = bytes(blocks, "Data");
        } else if (schematic.has("Palette") && schematic.has("BlockData")) {
            paletteTag = schematic.compound("Palette").orElseThrow(() -> new IOException("no block palette"));
            data = bytes(schematic, "BlockData");
        } else {
            throw new IOException("not a Sponge schematic (no palette or block data)");
        }

        int width = unsignedShort(schematic, "Width");
        int height = unsignedShort(schematic, "Height");
        int length = unsignedShort(schematic, "Length");
        long volume = (long) width * height * length;
        if (volume > MOST_BLOCKS) {
            throw new IOException("too large: " + width + " x " + height + " x " + length);
        }

        String[] palette = new String[paletteTag.size()];
        for (Map.Entry<String, Tag> entry : paletteTag.values().entrySet()) {
            if (!(entry.getValue() instanceof Tag.Int(int index)) || index < 0 || index >= palette.length) {
                throw new IOException("palette entry " + entry.getKey() + " has no usable index");
            }
            palette[index] = upgraded(entry.getKey());
        }
        for (int index = 0; index < palette.length; index++) {
            if (palette[index] == null) {
                throw new IOException("palette index " + index + " is missing");
            }
        }

        int[] blocks = new int[(int) volume];
        int read = 0;
        int at = 0;
        while (read < blocks.length && at < data.length) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (at >= data.length) {
                    throw new IOException("block data ends in the middle of a value");
                }
                byte next = data[at++];
                value |= (next & 0x7F) << shift;
                if ((next & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IOException("a block index longer than five bytes");
                }
            }
            if (value >= palette.length) {
                throw new IOException("block " + read + " points at " + value + ", past the palette of "
                        + palette.length);
            }
            blocks[read++] = value;
        }
        if (read < blocks.length) {
            throw new IOException("the size says " + blocks.length + " blocks, the data holds " + read);
        }
        return new Schematic(width, height, length, schematic.intOr("DataVersion", 0), Arrays.asList(palette),
                blocks);
    }

    /**
     * A schematic from block states in Sponge order ({@code x + z * width + y * width * length}) — what
     * {@link SchematicCapture} reads out of a world.
     */
    public static Schematic of(int width, int height, int length, int dataVersion, String[] states) {
        if ((long) width * height * length != states.length) {
            throw new IllegalArgumentException("states do not fill " + width + "x" + height + "x" + length);
        }
        Map<String, Integer> indexes = new java.util.LinkedHashMap<>();
        int[] blocks = new int[states.length];
        for (int index = 0; index < states.length; index++) {
            String state = states[index] == null ? AIR : states[index];
            blocks[index] = indexes.computeIfAbsent(state, added -> indexes.size());
        }
        return new Schematic(width, height, length, dataVersion, List.copyOf(indexes.keySet()), blocks);
    }

    /** As a Sponge schematic (version 2), the way WorldEdit and this class read it. */
    public Tag.Compound toTag() {
        Map<String, Tag> palette = new java.util.LinkedHashMap<>();
        for (int index = 0; index < this.palette.size(); index++) {
            palette.put(this.palette.get(index), new Tag.Int(index));
        }
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream(blocks.length);
        for (int value : blocks) {
            int rest = value;
            while ((rest & ~0x7F) != 0) {
                data.write((rest & 0x7F) | 0x80);
                rest >>>= 7;
            }
            data.write(rest);
        }
        Map<String, Tag> root = new java.util.LinkedHashMap<>();
        root.put("Version", new Tag.Int(2));
        root.put("DataVersion", new Tag.Int(dataVersion));
        root.put("Width", new Tag.Short((short) width));
        root.put("Height", new Tag.Short((short) height));
        root.put("Length", new Tag.Short((short) length));
        root.put("Offset", new Tag.IntArray(new int[]{0, 0, 0}));
        root.put("PaletteMax", new Tag.Int(palette.size()));
        root.put("Palette", new Tag.Compound(palette));
        root.put("BlockData", new Tag.ByteArray(data.toByteArray()));
        return new Tag.Compound(root);
    }

    /** Written to {@code file} (gzipped NBT), atomically — beside it, then moved over it. */
    public void write(Path file) throws IOException {
        Nbt.write(file, toTag());
    }

    public String stateAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
            return AIR;
        }
        return palette.get(blocks[x + z * width + y * width * length]);
    }

    /** Block ids from outside Minecraft itself — a mod's — which no plain server can place. Sorted, without states. */
    public List<String> foreign() {
        TreeSet<String> foreign = new TreeSet<>();
        for (String state : palette) {
            String id = id(state);
            if (!id.startsWith("minecraft:")) {
                foreign.add(id);
            }
        }
        return List.copyOf(foreign);
    }

    /**
     * Every block but air and foreign ones, with the schematic's corner at {@code origin}, layer by layer from the
     * bottom — so a torch or a carpet goes on after what it stands on.
     */
    public List<BatchBuilder.Placement> placements(Spot origin) {
        boolean[] skip = new boolean[palette.size()];
        for (int index = 0; index < skip.length; index++) {
            String id = id(palette.get(index));
            skip[index] = id.equals(AIR) || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air")
                    || !id.startsWith("minecraft:");
        }
        List<BatchBuilder.Placement> placements = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int index = blocks[x + z * width + y * width * length];
                    if (!skip[index]) {
                        placements.add(new BatchBuilder.Placement(
                                new Spot(origin.world(), origin.x() + x, origin.y() + y, origin.z() + z),
                                palette.get(index)));
                    }
                }
            }
        }
        return placements;
    }

    private static String id(String state) {
        int bracket = state.indexOf('[');
        String id = bracket < 0 ? state : state.substring(0, bracket);
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    private static String upgraded(String state) {
        int bracket = state.indexOf('[');
        String id = bracket < 0 ? state : state.substring(0, bracket);
        String renamed = RENAMED.get(id);
        return renamed == null ? state : renamed + (bracket < 0 ? "" : state.substring(bracket));
    }

    private static byte[] bytes(Tag.Compound compound, String name) throws IOException {
        if (compound.get(name).orElse(null) instanceof Tag.ByteArray array) {
            return array.value();
        }
        throw new IOException("no " + name + " in the schematic");
    }

    /** Sizes are shorts in the file but mean 0..65535. */
    private static int unsignedShort(Tag.Compound compound, String name) throws IOException {
        long value = compound.number(name).orElseThrow(() -> new IOException("no " + name + " in the schematic"));
        return (int) (value & 0xFFFF);
    }
}
