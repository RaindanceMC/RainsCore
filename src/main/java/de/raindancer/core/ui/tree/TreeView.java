package de.raindancer.core.ui.tree;

import java.util.Collection;

/**
 * The window onto a tree's canvas: {@link TreeGrid#COLUMNS} by {@link TreeGrid#ROWS} nodes at a time, scrolled a
 * column or a row at a time, never past the canvas's edges. Knows nothing about Minecraft.
 */
public final class TreeView {

    private final int width;
    private final int height;
    private int column;
    private int row;

    private TreeView(int width, int height) {
        this.width = Math.max(TreeGrid.COLUMNS, width);
        this.height = Math.max(TreeGrid.ROWS, height);
    }

    /** A window onto a canvas holding nodes at these {column, row}s. */
    public static TreeView of(Collection<int[]> places) {
        int width = 0;
        int height = 0;
        for (int[] place : places) {
            width = Math.max(width, place[0] + 1);
            height = Math.max(height, place[1] + 1);
        }
        return new TreeView(width, height);
    }

    public int column() {
        return column;
    }

    public int row() {
        return row;
    }

    public void scroll(int columns, int rows) {
        column = Math.max(0, Math.min(width - TreeGrid.COLUMNS, column + columns));
        row = Math.max(0, Math.min(height - TreeGrid.ROWS, row + rows));
    }

    /** Moves the window so it shows that node, as near the window's top left as the canvas allows. */
    public void lookAt(int nodeColumn, int nodeRow) {
        column = 0;
        row = 0;
        scroll(nodeColumn, nodeRow);
    }

    public boolean shows(int nodeColumn, int nodeRow) {
        return nodeColumn >= column && nodeColumn < column + TreeGrid.COLUMNS
                && nodeRow >= row && nodeRow < row + TreeGrid.ROWS;
    }

    /** Where a node the window shows sits in it: {column, row} from 0. */
    public int[] local(int nodeColumn, int nodeRow) {
        return new int[]{nodeColumn - column, nodeRow - row};
    }

    public boolean canScrollLeft() {
        return column > 0;
    }

    public boolean canScrollRight() {
        return column + TreeGrid.COLUMNS < width;
    }

    public boolean canScrollUp() {
        return row > 0;
    }

    public boolean canScrollDown() {
        return row + TreeGrid.ROWS < height;
    }

    public int hiddenLeft() {
        return column;
    }

    public int hiddenRight() {
        return width - TreeGrid.COLUMNS - column;
    }

    public int hiddenUp() {
        return row;
    }

    public int hiddenDown() {
        return height - TreeGrid.ROWS - row;
    }
}
