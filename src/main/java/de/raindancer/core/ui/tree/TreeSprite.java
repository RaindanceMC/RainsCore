package de.raindancer.core.ui.tree;

/**
 * What a tree's title draws: a node's frame in each of its states, and each shape of line, dim or lit. A look (see
 * {@link TreeLook}) paints every one of them; the order here is the order of their characters in its font.
 */
public enum TreeSprite {
    FRAME_LOCKED, FRAME_OPEN, FRAME_OWNED, FRAME_MAXED, FRAME_BARRED,
    ACROSS_DIM, ACROSS_LIT, DOWN_DIM, DOWN_LIT, FALLING_DIM, FALLING_LIT, RISING_DIM, RISING_LIT;

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

    /** The file name its painting is written under, {@code frame_owned}. */
    public String file() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
