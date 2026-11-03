package de.raindancer.core.ui.tree;

/** A branch of a {@link SkillTree}: one page of its {@link TreeMenu}. {@code icon} is a material's id. */
public record TreeBranch(String id, String title, String icon, String blurb) {
}
