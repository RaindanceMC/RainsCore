package de.raindancer.core.ui.tree;

/** How a node stands for one player. */
public enum NodeState {
    /** What it needs is not had yet. */
    LOCKED,
    /** Can be taken now. */
    OPEN,
    /** Taken, with ranks still to go. */
    OWNED,
    /** Taken all the way. */
    MAXED,
    /** Shut by a choice made elsewhere in the tree. */
    BARRED
}
