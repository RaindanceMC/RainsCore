package de.raindancer.core.ui.tree;

import net.kyori.adventure.key.Key;

/**
 * The font a tree's frames and lines are drawn in. A plugin with a look of its own ships a font with the same
 * characters (Core's {@code art/menus/tree.py} writes one from a set of paintings); {@link #BRASS} is Core's.
 */
public record TreeLook(Key font) {

    public static final TreeLook BRASS = new TreeLook(Key.key("rainscore", "tree"));
}
