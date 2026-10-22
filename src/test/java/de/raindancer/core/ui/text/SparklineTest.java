package de.raindancer.core.ui.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SparklineTest {

    @Test
    @DisplayName("lowest is the lowest bar, highest the highest, the rest in between")
    void scaled() {
        assertThat(Sparkline.of(new int[]{0, 70, 35, 10})).isEqualTo("▁█▄▂");
    }

    @Test
    @DisplayName("a gap stays a gap")
    void gaps() {
        assertThat(Sparkline.of(new int[]{-1, 10, -1, 20})).isEqualTo(" ▁ █");
    }

    @Test
    @DisplayName("flat stays low rather than jumping to full bars")
    void flat() {
        assertThat(Sparkline.of(new int[]{37, 37, 37})).isEqualTo("▁▁▁");
    }

    @Test
    @DisplayName("a smallest span keeps small wobbles small")
    void smallestSpan() {
        // 36..39 ms is nothing; without a span it would fill the whole height
        assertThat(Sparkline.of(new int[]{36, 39, 37})).isEqualTo("▁█▃");
        assertThat(Sparkline.of(new int[]{36, 39, 37}, 50)).isEqualTo("▁▁▁");
        assertThat(Sparkline.of(new int[]{100, 600}, 50)).isEqualTo("▁█");
    }

    @Test
    @DisplayName("nothing at all is an empty line")
    void empty() {
        assertThat(Sparkline.of(new int[0])).isEmpty();
        assertThat(Sparkline.of(new int[]{-1, -1})).isEqualTo("  ");
    }
}
