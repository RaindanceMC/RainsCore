package de.raindancer.core.ui.tree;

/**
 * Where a skill tree's nodes and the lines between them go in a six-row chest window. Knows nothing about Minecraft.
 *
 * <p>A tree is five columns by three rows of nodes. A node takes every other slot (slot row {@code 2r}, slot column
 * {@code 2c}), so between any two neighbours there is one free slot for the line joining them — across, down or
 * diagonal. Nodes that are not neighbours cannot be joined: their line would run through another node.
 *
 * <p>The lines and the nodes' frames are drawn by the window title (see {@link TreeGlyphs}), as sprites of
 * {@link #SPRITE} pixels square centred on their slot, so a line reaches into the frames at both its ends.
 */
public final class TreeGrid {

    public static final int COLUMNS = 5;
    public static final int ROWS = 3;
    /** Every sprite's side, in pixels. */
    public static final int SPRITE = 26;
    private static final int SLOT = 18;

    /** Which way a line runs through its slot. FALLING goes from top left to bottom right, RISING the other way. */
    public enum Shape { ACROSS, DOWN, FALLING, RISING }

    /** A line's slot, and how it runs there. */
    public record Link(int slotRow, int slotColumn, Shape shape) {
    }

    private TreeGrid() {
    }

    /** The slot of the node at tree column {@code column}, tree row {@code row}. */
    public static int slot(int column, int row) {
        check(column, row);
        return row * 2 * 9 + column * 2;
    }

    /** The line between two nodes; it does not matter which is named first. */
    public static Link link(int fromColumn, int fromRow, int toColumn, int toRow) {
        check(fromColumn, fromRow);
        check(toColumn, toRow);
        int dc = toColumn - fromColumn;
        int dr = toRow - fromRow;
        if (Math.abs(dc) > 1 || Math.abs(dr) > 1 || (dc == 0 && dr == 0)) {
            throw new IllegalArgumentException("Only neighbouring nodes can be joined, not (" + fromColumn + ","
                    + fromRow + ") and (" + toColumn + "," + toRow + ").");
        }
        int slotRow = fromRow * 2 + dr;
        int slotColumn = fromColumn * 2 + dc;
        Shape shape = dr == 0 ? Shape.ACROSS : dc == 0 ? Shape.DOWN : dc == dr ? Shape.FALLING : Shape.RISING;
        return new Link(slotRow, slotColumn, shape);
    }

    /** Where a sprite on this slot column starts, from the window's left edge. */
    public static int spriteX(int slotColumn) {
        return 16 + SLOT * slotColumn - SPRITE / 2;
    }

    /**
     * The font ascent that puts a sprite on this slot row. The title's baseline is 13 pixels down the window and a
     * glyph's top is drawn {@code ascent} above it; the sprite's top is 13 pixels above its slot's centre.
     */
    public static int spriteAscent(int slotRow) {
        return 13 - (26 + SLOT * slotRow - SPRITE / 2);
    }

    private static void check(int column, int row) {
        if (column < 0 || column >= COLUMNS || row < 0 || row >= ROWS) {
            throw new IllegalArgumentException("A tree is " + COLUMNS + " columns by " + ROWS + " rows; (" + column
                    + "," + row + ") is outside it.");
        }
    }
}
