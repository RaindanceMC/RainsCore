package de.raindancer.core.ui.tree;

import java.util.List;
import java.util.Map;

/**
 * A node of a {@link SkillTree}.
 *
 * @param column    on its tree's canvas, from 0, left to right
 * @param row       from 0, top to bottom
 * @param cost      points per rank; 0 for a node that is earned rather than bought, like a mastery badge
 * @param choice    one side of a choice: taking it shuts what it {@code excludes}
 * @param capstone  the end of its branch
 * @param level     the level its first rank needs, 0 for none
 * @param levelStep how many levels more each further rank needs
 * @param requires  one of these must be taken first
 * @param effects   what each rank adds, by the plugin's own effect ids
 * @param mastery   its branch's mastery badge: open once every other node of the branch not shut by a choice is at
 *                  its last rank
 */
public record TreeNode(String id, String branch, int column, int row, String title, String icon, int ranks, int cost,
                       boolean choice, boolean capstone, int level, int levelStep, List<String> requires,
                       List<String> excludes, Map<String, Double> effects, boolean mastery) {

    public TreeNode {
        requires = List.copyOf(requires);
        excludes = List.copyOf(excludes);
        effects = Map.copyOf(effects);
    }

    /** The level rank {@code rank} (from 1) needs; 0 for none. */
    public int levelFor(int rank) {
        if (level <= 0 && levelStep <= 0) {
            return 0;
        }
        return level + Math.max(0, rank - 1) * levelStep;
    }
}
