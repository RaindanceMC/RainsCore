package de.raindancer.core.content.advancement;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomAdvancementsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());

    /** What a server would hold: loaded keys in load order, and who has met which criterion. */
    private static final class FakeSink implements AdvancementSink {
        final Map<String, String> loaded = new LinkedHashMap<>();
        final Map<String, Set<String>> met = new HashMap<>();
        boolean refuse;

        @Override
        public Set<String> publish(Map<String, String> jsonByKey) {
            if (refuse) {
                return Set.of();
            }
            loaded.putAll(jsonByKey);
            return new HashSet<>(jsonByKey.keySet());
        }

        @Override
        public Set<String> met(UUID player, String key) {
            return met.getOrDefault(player + key, Set.of());
        }

        @Override
        public boolean grant(UUID player, String key, String criterion) {
            return met.computeIfAbsent(player + key, k -> new HashSet<>()).add(criterion);
        }

        @Override
        public boolean revoke(UUID player, String key, String criterion) {
            return met.getOrDefault(player + key, new HashSet<>()).remove(criterion);
        }
    }

    private static AdvancementDef def(String id, String parent, int steps) {
        return AdvancementDef.builder("enchants", id).title(id).icon(Material.STONE).parent(parent)
                .steps(steps).build();
    }

    private final FakeSink sink = new FakeSink();
    private final CustomAdvancements advancements = new CustomAdvancements(sink);

    @Test
    @DisplayName("a definition is loaded the moment it is made")
    void loadsAtOnce() {
        advancements.define(def("root", null, 1));

        assertThat(sink.loaded).containsKey("enchants:root");
        assertThat(advancements.isLoaded("enchants:root")).isTrue();
    }

    @Test
    @DisplayName("a child defined before its parent waits, then loads once the parent is there")
    void childBeforeParent() {
        advancements.define(def("child", "root", 1));
        assertThat(sink.loaded).isEmpty();
        assertThat(advancements.isLoaded("enchants:child")).isFalse();

        advancements.define(def("root", null, 1));

        assertThat(sink.loaded.keySet()).containsExactly("enchants:root", "enchants:child");
    }

    @Test
    @DisplayName("a parent in another namespace is not waited for")
    void foreignParent() {
        advancements.define(def("x", "minecraft:story/root", 1));

        assertThat(sink.loaded).containsKey("enchants:x");
    }

    @Test
    @DisplayName("awarding is true once, false for every repeat")
    void awardOnce() {
        advancements.define(def("root", null, 1));

        assertThat(advancements.award(ALICE, "enchants:root")).isTrue();
        assertThat(advancements.award(ALICE, "enchants:root")).isFalse();
        assertThat(advancements.has(ALICE, "enchants:root")).isTrue();
    }

    @Test
    @DisplayName("progress completes only when the last step is reached, and reports that once")
    void progress() {
        advancements.define(def("many", null, 3));

        assertThat(advancements.progress(ALICE, "enchants:many", 1)).isFalse();
        assertThat(advancements.stepsDone(ALICE, "enchants:many")).isEqualTo(1);
        assertThat(advancements.has(ALICE, "enchants:many")).isFalse();
        assertThat(advancements.progress(ALICE, "enchants:many", 3)).isTrue();
        assertThat(advancements.progress(ALICE, "enchants:many", 3)).isFalse();
        assertThat(advancements.has(ALICE, "enchants:many")).isTrue();
    }

    @Test
    @DisplayName("progress never goes backwards and never past the goal")
    void progressClamps() {
        advancements.define(def("many", null, 2));

        advancements.progress(ALICE, "enchants:many", 2);
        advancements.progress(ALICE, "enchants:many", 1);
        advancements.progress(ALICE, "enchants:many", 99);

        assertThat(advancements.stepsDone(ALICE, "enchants:many")).isEqualTo(2);
    }

    @Test
    @DisplayName("an advancement nobody defined, or one the server refused, is never reported as earned")
    void unknownAndRefused() {
        assertThat(advancements.award(ALICE, "enchants:nope")).isFalse();

        sink.refuse = true;
        advancements.define(def("root", null, 1));

        assertThat(advancements.isLoaded("enchants:root")).isFalse();
        assertThat(advancements.award(ALICE, "enchants:root")).isFalse();
    }

    @Test
    @DisplayName("a reload of server data loads everything again, parents first")
    void republish() {
        advancements.define(def("root", null, 1));
        advancements.define(def("child", "root", 1));
        sink.loaded.clear();

        advancements.republish();

        assertThat(List.copyOf(sink.loaded.keySet())).containsExactly("enchants:root", "enchants:child");
    }

    @Test
    @DisplayName("revoke takes every step back")
    void revoke() {
        advancements.define(def("many", null, 2));
        advancements.progress(ALICE, "enchants:many", 2);

        assertThat(advancements.revoke(ALICE, "enchants:many")).isTrue();
        assertThat(advancements.stepsDone(ALICE, "enchants:many")).isZero();
        assertThat(advancements.revoke(ALICE, "enchants:many")).isFalse();
    }
}
