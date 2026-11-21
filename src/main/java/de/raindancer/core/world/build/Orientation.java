package de.raindancer.core.world.build;

import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;

import java.util.Optional;

/**
 * How a schematic is turned and mirrored before it is put down — the same two choices a structure block offers.
 *
 * <p>Mirrored first (east and west swap), then turned clockwise as seen from above, in quarter turns. Positions are
 * worked out here without a server; block states (a stair's facing, a log's axis) are turned by
 * {@link #applyTo(BlockData)}, which asks the game, because the game knows every block's properties and a table
 * kept here would not.
 *
 * @param turns    quarter turns clockwise, folded into 0..3
 * @param mirrored whether east and west swap before turning
 */
public record Orientation(int turns, boolean mirrored) {

    public static final Orientation NONE = new Orientation(0, false);

    public Orientation {
        turns = Math.floorMod(turns, 4);
    }

    /** A quarter turn further clockwise. */
    public Orientation turned() {
        return new Orientation(turns + 1, mirrored);
    }

    /** Mirrored the other way. */
    public Orientation flipped() {
        return new Orientation(turns, !mirrored);
    }

    public int degrees() {
        return turns * 90;
    }

    /** How wide (along x) a {@code width × length} footprint is once turned. */
    public int width(int width, int length) {
        return turns % 2 == 0 ? width : length;
    }

    /** How long (along z) a {@code width × length} footprint is once turned. */
    public int length(int width, int length) {
        return turns % 2 == 0 ? length : width;
    }

    /**
     * Where the schematic's block at {@code (x, y, z)} goes, as an offset from the turned footprint's north-west
     * bottom corner.
     */
    public int[] place(int x, int y, int z, int width, int length) {
        int px = mirrored ? width - 1 - x : x;
        int pz = z;
        int w = width;
        int l = length;
        for (int turn = 0; turn < turns; turn++) {
            // Clockwise from above: north (0,-1) becomes east (1,0).
            int nx = l - 1 - pz;
            int nz = px;
            px = nx;
            pz = nz;
            int swap = w;
            w = l;
            l = swap;
        }
        return new int[]{px, y, pz};
    }

    /** Turns and mirrors a block's own properties to match, in place. */
    public void applyTo(BlockData data) {
        if (mirrored) {
            data.mirror(Mirror.FRONT_BACK);
        }
        data.rotate(switch (turns) {
            case 1 -> StructureRotation.CLOCKWISE_90;
            case 2 -> StructureRotation.CLOCKWISE_180;
            case 3 -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        });
    }

    /** {@code 90} or {@code 90m}: kept in a file and read back with {@link #parse}. */
    public String toText() {
        return degrees() + (mirrored ? "m" : "");
    }

    public static Optional<Orientation> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        boolean mirrored = trimmed.endsWith("m");
        String number = mirrored ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        try {
            int degrees = Integer.parseInt(number);
            if (degrees % 90 != 0) {
                return Optional.empty();
            }
            return Optional.of(new Orientation(degrees / 90, mirrored));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }
}
