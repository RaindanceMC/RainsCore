package de.raindancer.core.ui.tree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillTreeTest {

    private static final Set<String> EFFECTS = Set.of("speed", "luck", "gold");

    private static final String TREE = """
            branches:
              smith: {title: Smith, icon: anvil, blurb: Hammers.}
              thief: {title: Thief, icon: feather}
            nodes:
              root: {branch: smith, at: [0, 1], title: Root, ranks: 3, effects: {speed: 5}}
              upper: {branch: smith, at: [1, 0], title: Upper, requires: [root], cost: 2, effects: {luck: 1}}
              lower: {branch: smith, at: [1, 2], title: Lower, requires: [root], effects: {gold: 10}}
              left: {branch: smith, at: [2, 0], choice: true, requires: [upper], excludes: [right], effects: {speed: 20, luck: -1}}
              right: {branch: smith, at: [2, 2], choice: true, requires: [lower], excludes: [left], effects: {gold: 40}}
              top: {branch: smith, at: [2, 1], capstone: true, level: 10, level-step: 5, ranks: 2, requires: [upper, lower], effects: {gold: 1}}
              sneak: {branch: thief, at: [0, 1], effects: {speed: 1}}
            """;

    private final SkillTree tree = SkillTree.read(TREE, EFFECTS::contains);

    @Test
    @DisplayName("branches and nodes are read in their order, with every field")
    void reads() {
        assertThat(tree.branches()).extracting(TreeBranch::id).containsExactly("smith", "thief");
        assertThat(tree.branches().getFirst().blurb()).isEqualTo("Hammers.");
        assertThat(tree.nodesOf("smith")).extracting(TreeNode::id)
                .containsExactly("root", "upper", "lower", "left", "right", "top");
        TreeNode top = tree.node("top").orElseThrow();
        assertThat(top.capstone()).isTrue();
        assertThat(top.ranks()).isEqualTo(2);
        assertThat(top.levelFor(1)).isEqualTo(10);
        assertThat(top.levelFor(2)).as("level-step more per rank").isEqualTo(15);
        assertThat(tree.node("upper").orElseThrow().cost()).isEqualTo(2);
        assertThat(tree.node("left").orElseThrow().effects()).containsEntry("luck", -1.0);
    }

    @Test
    @DisplayName("effects add up rank for rank; unknown nodes and ranks past the last count for nothing more")
    void effects() {
        Map<String, Double> sum = tree.effects(Map.of("root", 9, "left", 1, "gone", 4));
        assertThat(sum).containsEntry("speed", 35.0).containsEntry("luck", -1.0).doesNotContainKey("gold");
    }

    @Test
    @DisplayName("points spent: cost times ranks taken, capped at the node's last rank")
    void spent() {
        assertThat(tree.spent(Map.of("root", 2, "upper", 1, "gone", 3))).isEqualTo(4);
        assertThat(tree.spent(Map.of("root", 7))).isEqualTo(3);
    }

    @Test
    @DisplayName("a node is open once one neighbour it needs is taken, barred while the other side of its choice is")
    void states() {
        assertThat(tree.state(Map.of(), tree.node("root").orElseThrow())).isEqualTo(NodeState.OPEN);
        assertThat(tree.state(Map.of(), tree.node("upper").orElseThrow())).isEqualTo(NodeState.LOCKED);
        assertThat(tree.state(Map.of("root", 1), tree.node("upper").orElseThrow())).isEqualTo(NodeState.OPEN);
        assertThat(tree.state(Map.of("root", 1), tree.node("root").orElseThrow())).isEqualTo(NodeState.OWNED);
        assertThat(tree.state(Map.of("root", 3), tree.node("root").orElseThrow())).isEqualTo(NodeState.MAXED);
        assertThat(tree.state(Map.of("root", 1, "lower", 1, "right", 1), tree.node("left").orElseThrow()))
                .isEqualTo(NodeState.BARRED);
    }

    @Test
    @DisplayName("refusals, in order: maxed, barred, needs, level, points")
    void refusals() {
        TreeNode top = tree.node("top").orElseThrow();
        assertThat(tree.refusal(Map.of("root", 3), tree.node("root").orElseThrow(), 99, 99))
                .contains(SkillTree.Refusal.MAXED);
        assertThat(tree.refusal(Map.of("right", 1, "upper", 1), tree.node("left").orElseThrow(), 99, 99))
                .contains(SkillTree.Refusal.BARRED);
        assertThat(tree.refusal(Map.of(), top, 99, 99)).contains(SkillTree.Refusal.NEEDS);
        assertThat(tree.refusal(Map.of("upper", 1), top, 9, 99)).contains(SkillTree.Refusal.LEVEL);
        assertThat(tree.refusal(Map.of("upper", 1, "top", 1), top, 14, 99))
                .as("rank 2 needs level 15").contains(SkillTree.Refusal.LEVEL);
        assertThat(tree.refusal(Map.of("upper", 1), top, 10, 0)).contains(SkillTree.Refusal.NO_POINTS);
        assertThat(tree.refusal(Map.of("upper", 1), top, 10, 1)).isEmpty();
        assertThat(tree.refusal(Map.of("upper", 1), tree.node("left").orElseThrow(), 0, 1))
                .as("nothing needs a level here").isEmpty();
    }

    @Test
    @DisplayName("a rank already taken is never taken back for lost needs: the next rank only asks for level and points")
    void ownedKeepsGoing() {
        assertThat(tree.refusal(Map.of("top", 1), tree.node("top").orElseThrow(), 20, 1)).isEmpty();
    }

    @Test
    @DisplayName("a broken tree is refused as it is read, saying what is wrong")
    void refusesBrokenTrees() {
        assertThatThrownBy(() -> SkillTree.read("nodes: {}", EFFECTS::contains)).hasMessageContaining("branches");
        String head = "branches: {a: {title: A}}\nnodes:\n";
        assertThatThrownBy(() -> SkillTree.read(head + "  x: {branch: b, at: [0, 0]}", EFFECTS::contains))
                .hasMessageContaining("no known branch");
        assertThatThrownBy(() -> SkillTree.read(head + "  x: {branch: a, at: [5, 0]}", EFFECTS::contains))
                .hasMessageContaining("x");
        assertThatThrownBy(() -> SkillTree.read(head + "  x: {branch: a, at: [0, 0]}\n  y: {branch: a, at: [0, 0]}",
                EFFECTS::contains)).hasMessageContaining("sits where");
        assertThatThrownBy(() -> SkillTree.read(head + "  x: {branch: a, at: [0, 0]}\n  y: {branch: a, at: [2, 0], requires: [x]}",
                EFFECTS::contains)).hasMessageContaining("not its neighbour");
        assertThatThrownBy(() -> SkillTree.read(head + "  x: {branch: a, at: [0, 0], effects: {flying: 1}}",
                EFFECTS::contains)).hasMessageContaining("flying");
        assertThatThrownBy(() -> SkillTree.read(head + "  x: {branch: a, at: [0, 0], excludes: [nope]}",
                EFFECTS::contains)).hasMessageContaining("nope");
    }
}
