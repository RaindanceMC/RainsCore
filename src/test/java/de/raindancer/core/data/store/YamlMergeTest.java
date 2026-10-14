package de.raindancer.core.data.store;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YamlMergeTest {

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException broken) {
            throw new IllegalArgumentException(broken);
        }
        return yaml;
    }

    @Test
    @DisplayName("two servers changing different players: both changes are kept")
    void disjointChanges() {
        YamlConfiguration base = yaml("players:\n  alex: 1\n  bea: 1\n");
        YamlConfiguration mine = yaml("players:\n  alex: 5\n  bea: 1\n");
        YamlConfiguration theirs = yaml("players:\n  alex: 1\n  bea: 9\n");
        YamlMerge.Result merged = YamlMerge.threeWay(base, mine, theirs);
        assertThat(merged.yaml().getInt("players.alex")).isEqualTo(5);
        assertThat(merged.yaml().getInt("players.bea")).isEqualTo(9);
        assertThat(merged.conflicts()).isEmpty();
    }

    @Test
    @DisplayName("both changed the same value: mine wins (it is the newer write) and the conflict is named")
    void sameValueChangedOnBoth() {
        YamlConfiguration base = yaml("x: 1\n");
        YamlMerge.Result merged = YamlMerge.threeWay(base, yaml("x: 2\n"), yaml("x: 3\n"));
        assertThat(merged.yaml().getInt("x")).isEqualTo(2);
        assertThat(merged.conflicts()).containsExactly("x");
    }

    @Test
    @DisplayName("a key I removed is removed; a key they added is kept")
    void removalsAndAdditions() {
        YamlConfiguration base = yaml("a: 1\nb: 2\n");
        YamlConfiguration mine = yaml("a: 1\n");
        YamlConfiguration theirs = yaml("a: 1\nb: 2\nc: 3\n");
        YamlMerge.Result merged = YamlMerge.threeWay(base, mine, theirs);
        assertThat(merged.yaml().contains("b")).isFalse();
        assertThat(merged.yaml().getInt("c")).isEqualTo(3);
    }

    @Test
    @DisplayName("a whole section I removed goes, with everything under it")
    void sectionRemoved() {
        YamlConfiguration base = yaml("claims:\n  one:\n    owner: alex\n    size: 4\n  two:\n    owner: bea\n");
        YamlConfiguration mine = yaml("claims:\n  two:\n    owner: bea\n");
        YamlMerge.Result merged = YamlMerge.threeWay(base, mine, base);
        assertThat(merged.yaml().contains("claims.one")).isFalse();
        assertThat(merged.yaml().getString("claims.two.owner")).isEqualTo("bea");
    }

    @Test
    @DisplayName("lists are one value: replaced whole, never interleaved")
    void listsAreValues() {
        YamlConfiguration base = yaml("l: [a, b]\n");
        YamlMerge.Result merged = YamlMerge.threeWay(base, yaml("l: [a, b, c]\n"), yaml("l: [a]\n"));
        assertThat(merged.yaml().getStringList("l")).containsExactly("a", "b", "c");
        assertThat(merged.conflicts()).containsExactly("l");
    }

    @Test
    @DisplayName("a value that became a section, and the other way round")
    void typeChanges() {
        YamlConfiguration base = yaml("k: 1\n");
        YamlMerge.Result merged = YamlMerge.threeWay(base, yaml("k:\n  inner: 2\n"), base);
        assertThat(merged.yaml().getInt("k.inner")).isEqualTo(2);
        YamlMerge.Result back = YamlMerge.threeWay(yaml("k:\n  inner: 2\n"), yaml("k: 1\n"), yaml("k:\n  inner: 2\n  more: 3\n"));
        assertThat(back.yaml().getInt("k")).isEqualTo(1);
    }

    @Test
    @DisplayName("importing a file into what is already there: what is there wins, the file adds the rest")
    void importKeepsWhatIsThere() {
        YamlConfiguration file = yaml("nick:\n  alex: Lexi\n  carl: Carlo\n");
        YamlConfiguration stored = yaml("nick:\n  alex: Alexandra\n  bea: Bee\n");
        YamlMerge.Result merged = YamlMerge.importInto(file, stored);
        assertThat(merged.yaml().getString("nick.alex")).isEqualTo("Alexandra");
        assertThat(merged.yaml().getString("nick.bea")).isEqualTo("Bee");
        assertThat(merged.yaml().getString("nick.carl")).isEqualTo("Carlo");
        assertThat(merged.conflicts()).containsExactly("nick.alex");
    }

    @Test
    @DisplayName("nothing changed on either side is the same document")
    void noChange() {
        YamlConfiguration base = yaml("a: 1\nb:\n  c: [1, 2]\n");
        YamlMerge.Result merged = YamlMerge.threeWay(base, base, base);
        assertThat(merged.yaml().saveToString()).isEqualTo(base.saveToString());
        assertThat(merged.conflicts()).isEmpty();
    }

    @Test
    @DisplayName("keys with dots kept as path parts survive the merge")
    void dottedPathParts() {
        String key = YamlStore.asPathPart("speedrun.any%");
        YamlConfiguration mine = new YamlConfiguration();
        mine.set("records." + key, 42);
        YamlMerge.Result merged = YamlMerge.threeWay(new YamlConfiguration(), mine, new YamlConfiguration());
        assertThat(merged.yaml().getInt("records." + key)).isEqualTo(42);
        assertThat(List.copyOf(merged.yaml().getConfigurationSection("records").getKeys(false))).containsExactly(key);
    }
}
