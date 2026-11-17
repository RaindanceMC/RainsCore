package de.raindancer.core.ui.statue;

import de.raindancer.core.data.store.YamlStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatueSpotsTest {

    /** The three richest statues standing on Hyades, as Economy's settings hold them. */
    private static final List<String> HYADES = List.of(
            "world 4229.50 72.00 -1493.50 135.0 richest 1 -",
            "world 4231.50 72.00 -1494.50 90.0 richest 3 -",
            "world 4227.50 72.00 -1492.50 180.0 richest 2 -");

    @TempDir
    Path folder;

    private StatueSpots fresh() {
        StatueSpots spots = new StatueSpots(new YamlStore(folder.resolve("statues.yml")));
        spots.load();
        return spots;
    }

    @Test
    @DisplayName("Economy's statues are taken over once, exactly where they stand, and are there after a restart")
    void adoptsEconomysStatues() {
        StatueSpots spots = fresh();
        StatueSpots.Adoption adoption = spots.adopt("economy", HYADES);
        assertThat(adoption.already()).isFalse();
        assertThat(adoption.taken()).isEqualTo(3);
        assertThat(adoption.saved()).isTrue();
        assertThat(spots.all()).extracting(StatueSpot::written).containsExactlyElementsOf(HYADES);
        assertThat(spots.adopted("economy")).isTrue();

        StatueSpots restarted = fresh();
        assertThat(restarted.all()).extracting(StatueSpot::written).containsExactlyElementsOf(HYADES);
        assertThat(restarted.adopted("economy")).isTrue();
    }

    @Test
    @DisplayName("taking over again changes nothing: no statue twice, and one removed since does not come back")
    void adoptsOnce() {
        StatueSpots spots = fresh();
        spots.adopt("economy", HYADES);
        assertThat(spots.change(all -> {
            List<StatueSpot> fewer = new ArrayList<>(all);
            fewer.removeIf(spot -> spot.place() == 3);
            return fewer;
        })).isTrue();

        StatueSpots.Adoption again = fresh().adopt("economy", HYADES);
        assertThat(again.already()).isTrue();
        assertThat(again.taken()).isZero();
        assertThat(fresh().all()).extracting(StatueSpot::place).containsExactly(1, 2);
    }

    @Test
    @DisplayName("statues placed in Core already stay, and one that is there already is not put up twice")
    void mergesWithWhatIsThere() {
        StatueSpots spots = fresh();
        StatueSpot prime = StatueSpot.parse("world 10.50 64.00 10.50 0.0 prime 1 -").orElseThrow();
        StatueSpot richest = StatueSpot.parse(HYADES.getFirst()).orElseThrow();
        spots.change(all -> List.of(prime, richest));

        StatueSpots.Adoption adoption = spots.adopt("economy", HYADES);
        assertThat(adoption.taken()).isEqualTo(2);
        assertThat(spots.all()).hasSize(4).contains(prime);
        assertThat(spots.all().stream().filter(spot -> spot.key().equals(richest.key()))).hasSize(1);
    }

    @Test
    @DisplayName("an old podium line is taken over as its three statues; a line that cannot be read is counted, not kept")
    void legacyAndBroken() {
        StatueSpots.Adoption adoption = fresh().adopt("economy",
                List.of("casino 10.50 64.00 -3.25 90.0 casino", "garbled line", " "));
        assertThat(adoption.taken()).isEqualTo(3);
        assertThat(adoption.unreadable()).isEqualTo(1);
        assertThat(fresh().all()).extracting(StatueSpot::board).containsOnly("casino");
    }

    @Test
    @DisplayName("nothing to take over is not remembered, so statues read on a later start are still taken over")
    void nothingToAdopt() {
        StatueSpots spots = fresh();
        StatueSpots.Adoption nothing = spots.adopt("economy", List.of());
        assertThat(nothing.taken()).isZero();
        assertThat(nothing.saved()).isTrue();
        assertThat(spots.adopted("economy")).isFalse();
        assertThat(spots.adopt("economy", List.of("garbled")).unreadable()).isEqualTo(1);
        assertThat(spots.adopted("economy")).isFalse();
        assertThat(fresh().adopt("economy", HYADES).taken()).isEqualTo(3);
        assertThat(fresh().adopted("economy")).isTrue();
    }

    @Test
    @DisplayName("a document that cannot be read is neither taken over into nor changed, so what it holds is not overwritten")
    void unreadableIsLeftAlone() throws Exception {
        Path file = folder.resolve("statues.yml");
        Files.writeString(file, "spots: [unclosed\n  - : :\n");
        StatueSpots spots = new StatueSpots(new YamlStore(file));
        assertThat(spots.load()).isFalse();
        assertThat(spots.adopt("economy", HYADES).saved()).isFalse();
        assertThat(spots.adopted("economy")).isFalse();
        assertThat(spots.change(all -> List.of())).isFalse();
        assertThat(Files.readString(file)).startsWith("spots: [unclosed");
    }
}
