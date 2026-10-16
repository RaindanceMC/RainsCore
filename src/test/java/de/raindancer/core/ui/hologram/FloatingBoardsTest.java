package de.raindancer.core.ui.hologram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("where floating boards stand, as written to their file")
class FloatingBoardsTest {

    @Test
    @DisplayName("a spot is one line: world and three numbers, read back the same")
    void roundTrip() {
        FloatingBoards.Spot spot = new FloatingBoards.Spot("lobby", 10.25, 66.2, -4.5);
        String line = FloatingBoards.line(spot);
        assertThat(line).isEqualTo("lobby 10.25 66.20 -4.50");
        assertThat(FloatingBoards.parse(line)).contains(new FloatingBoards.Spot("lobby", 10.25, 66.2, -4.5));
    }

    @Test
    @DisplayName("a garbled line is left out rather than failing the rest")
    void garbled() {
        assertThat(FloatingBoards.parse("lobby x 1 2")).isEmpty();
        assertThat(FloatingBoards.parse("lobby 1 2")).isEmpty();
        assertThat(FloatingBoards.parse(null)).isEmpty();
        List<Optional<FloatingBoards.Spot>> read = List.of(FloatingBoards.parse("a 1 2 3"), FloatingBoards.parse(""));
        assertThat(read.get(0)).isPresent();
        assertThat(read.get(1)).isEmpty();
    }

    @Test
    @DisplayName("a world name with spaces cannot be written, so it is never accepted")
    void spaces() {
        assertThat(FloatingBoards.parse("my world 1 2 3")).isEmpty();
    }
}
