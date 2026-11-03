package de.raindancer.core.ui.tree;

import java.util.List;

/**
 * Writes a tree's frames and lines as one run of a look's font, for the window title to draw over the painting.
 *
 * <p>The font has one character per sprite and slot row — a bitmap's ascent is fixed, so each row needs its own —
 * starting at {@link #FIRST_SPRITE}, and spaces of ±1, 2, 4 … 128 pixels from {@link #FIRST_SPACE} to move the pen.
 * A bitmap glyph moves the pen its width and one more. The run starts and ends where the title's text starts.
 */
public final class TreeGlyphs {

    /** Where the title's text starts, from the window's left edge. */
    public static final int START = 8;
    public static final char FIRST_SPRITE = '';
    /** Slot rows a sprite has characters for: the five above the chrome row. */
    public static final int ROWS_PER_SPRITE = 5;
    /** {@code FIRST_SPACE + i} moves back {@code 2^i}, {@code FIRST_SPACE + 8 + i} forward {@code 2^i}. */
    public static final char FIRST_SPACE = '';
    private static final int POWERS = 8;

    /** A sprite on a slot. */
    public record Placement(TreeSprite sprite, int slotRow, int slotColumn) {
    }

    private TreeGlyphs() {
    }

    public static char character(TreeSprite sprite, int slotRow) {
        if (slotRow < 0 || slotRow >= ROWS_PER_SPRITE) {
            throw new IllegalArgumentException("Tree sprites go on slot rows 0 to 4, not " + slotRow + ".");
        }
        return (char) (FIRST_SPRITE + sprite.ordinal() * ROWS_PER_SPRITE + slotRow);
    }

    public static String draw(List<Placement> placements) {
        if (placements.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int pen = START;
        for (Placement placement : placements) {
            int x = TreeGrid.spriteX(placement.slotColumn());
            out.append(move(x - pen)).append(character(placement.sprite(), placement.slotRow()));
            pen = x + TreeGrid.SPRITE + 1;
        }
        return out.append(move(START - pen)).toString();
    }

    /** Spaces that move the pen {@code distance} pixels, back for a negative one. */
    public static String move(int distance) {
        StringBuilder out = new StringBuilder();
        int left = Math.abs(distance);
        int base = distance < 0 ? 0 : POWERS;
        for (int power = POWERS - 1; power >= 0; power--) {
            int step = 1 << power;
            while (left >= step) {
                out.append((char) (FIRST_SPACE + base + power));
                left -= step;
            }
        }
        return out.toString();
    }

    /** What a space character moves the pen, or {@code null} for anything else. */
    public static Integer advance(char c) {
        int index = c - FIRST_SPACE;
        if (index < 0 || index >= POWERS * 2) {
            return null;
        }
        int step = 1 << (index % POWERS);
        return index < POWERS ? -step : step;
    }
}
