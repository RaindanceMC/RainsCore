package de.raindancer.core.ui.tree;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LearnerPriceTest {

    private static final TreeNode NODE = new TreeNode("jump", "main", 0, 0, "Jump", "FEATHER", 3, 2, false, false, 0, 0,
            List.of(), List.of(), Map.of(), false);

    private static class Plain implements LearningMenu.Learner {
        public SkillTree tree() { return null; }
        public String title() { return "t"; }
        public String blurb() { return ""; }
        public Material icon() { return Material.STONE; }
        public Map<String, Integer> ranks() { return Map.of(); }
        public int level() { return 0; }
        public String levelWord() { return "level"; }
        public int points() { return 0; }
        public int pointsLeft() { return 0; }
        public String describe(String effect, double value) { return null; }
        public Optional<SkillTree.Refusal> refusal(String node) { return Optional.empty(); }
        public Optional<SkillTree.Refusal> learn(String node) { return Optional.empty(); }
        public long resetAgainAt() { return 0; }
        public boolean reset() { return true; }
    }

    @Test
    @DisplayName("a learner that says nothing keeps the points wording, the summary and the reset")
    void defaultsAreTheOldBehaviour() {
        LearningMenu.Learner learner = new Plain();
        assertThat(learner.price(NODE, 1)).isNull();
        assertThat(learner.notEnough(NODE, 1)).isNull();
        assertThat(learner.showsPoints()).isTrue();
        assertThat(learner.resettable()).isTrue();
        assertThat(LearningMenu.priceLine(learner, NODE, 1)).contains("2").contains("points a rank");
        assertThat(LearningMenu.reasonFor(learner, SkillTree.Refusal.NO_POINTS, NODE, 1))
                .isEqualTo("Not enough points.");
    }

    @Test
    @DisplayName("a learner with its own price and wording replaces the points text")
    void ownPrice() {
        LearningMenu.Learner learner = new Plain() {
            public String price(TreeNode node, int nextRank) { return "4 shards + $20k"; }
            public String notEnough(TreeNode node, int nextRank) { return "You need 4 shards."; }
        };
        assertThat(LearningMenu.priceLine(learner, NODE, 1)).contains("4 shards + $20k").doesNotContain("points");
        assertThat(LearningMenu.reasonFor(learner, SkillTree.Refusal.NO_POINTS, NODE, 1))
                .isEqualTo("You need 4 shards.");
        assertThat(LearningMenu.reasonFor(learner, SkillTree.Refusal.MAXED, NODE, 3)).isEqualTo("At its highest rank.");
    }
}
