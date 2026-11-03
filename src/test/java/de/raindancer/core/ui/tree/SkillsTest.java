package de.raindancer.core.ui.tree;

import de.raindancer.core.ui.menu.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SkillsTest {

    private record Fake(String id, String title, int order) implements SkillView {
        @Override
        public Material icon() {
            return Material.STONE;
        }

        @Override
        public int level(UUID player) {
            return 3;
        }

        @Override
        public double progress(UUID player) {
            return 0.5;
        }

        @Override
        public int pointsLeft(UUID player) {
            return 1;
        }

        @Override
        public int branches() {
            return 3;
        }

        @Override
        public int mastered(UUID player) {
            return 1;
        }

        @Override
        public void open(Player viewer, Menu parent) {
        }
    }

    @AfterEach
    void clear() {
        Skills.clear();
    }

    @Test
    @DisplayName("skills are listed by their order, then title; one registered again under its id replaces the first")
    void listing() {
        Skills.register(null, new Fake("mining", "Mining", 20));
        Skills.register(null, new Fake("fishing", "Fishing", 10));
        Skills.register(null, new Fake("archery", "Archery", 20));
        assertThat(Skills.all()).extracting(SkillView::id).containsExactly("fishing", "archery", "mining");
        Skills.register(null, new Fake("mining", "Mining II", 5));
        assertThat(Skills.all()).extracting(SkillView::id).containsExactly("mining", "fishing", "archery");
        assertThat(Skills.byId("mining")).map(SkillView::title).contains("Mining II");
        assertThat(Skills.unregister("mining")).isTrue();
        assertThat(Skills.byId("mining")).isEmpty();
    }

    @Test
    @DisplayName("a skill is fully mastered when every branch is")
    void mastery() {
        SkillView view = new Fake("x", "X", 0);
        assertThat(view.fullyMastered(UUID.randomUUID())).isFalse();
    }
}
