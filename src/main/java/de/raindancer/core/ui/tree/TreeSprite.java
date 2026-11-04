package de.raindancer.core.ui.tree;

/**
 * What a tree's title draws: a node's frame in each of its states, and each shape of line, dim or lit. A look (see
 * {@link TreeLook}) paints every one of them; the order here is the order of their characters in its font, so new
 * sprites only ever go at the end — a look painted before them keeps every character it has.
 */
public enum TreeSprite {
    FRAME_LOCKED, FRAME_OPEN, FRAME_OWNED, FRAME_MAXED, FRAME_BARRED,
    ACROSS_DIM, ACROSS_LIT, DOWN_DIM, DOWN_LIT, FALLING_DIM, FALLING_LIT, RISING_DIM, RISING_LIT,
    /** A branch's mastery badge: not yet earned, open to take, taken. */
    MASTERY_LOCKED, MASTERY_OPEN, MASTERY_MAXED;

    /** A node's frame; a mastery badge has its own, locked, open or taken. */
    public static TreeSprite frame(NodeState state, boolean mastery) {
        if (!mastery) {
            return frame(state);
        }
        return switch (state) {
            case OPEN -> MASTERY_OPEN;
            case OWNED, MAXED -> MASTERY_MAXED;
            case LOCKED, BARRED -> MASTERY_LOCKED;
        };
    }

    public static TreeSprite frame(NodeState state) {
        return switch (state) {
            case LOCKED -> FRAME_LOCKED;
            case OPEN -> FRAME_OPEN;
            case OWNED -> FRAME_OWNED;
            case MAXED -> FRAME_MAXED;
            case BARRED -> FRAME_BARRED;
        };
    }

    public static TreeSprite line(TreeGrid.Shape shape, boolean lit) {
        return switch (shape) {
            case ACROSS -> lit ? ACROSS_LIT : ACROSS_DIM;
            case DOWN -> lit ? DOWN_LIT : DOWN_DIM;
            case FALLING -> lit ? FALLING_LIT : FALLING_DIM;
            case RISING -> lit ? RISING_LIT : RISING_DIM;
        };
    }

    /** A mastery badge's side, in pixels: a frame's, so the item sits in it like in any node. */
    public static final int MASTERY_SIZE = TreeGrid.SPRITE;

    /** Its side, in pixels: {@link TreeGrid#SPRITE} but for a mastery badge. */
    public int size() {
        return name().startsWith("MASTERY_") ? MASTERY_SIZE : TreeGrid.SPRITE;
    }

    /** The file name its painting is written under, {@code frame_owned}. */
    public String file() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
