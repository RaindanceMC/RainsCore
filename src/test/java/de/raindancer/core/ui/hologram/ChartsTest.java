package de.raindancer.core.ui.hologram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Charts drawn in text: a bar per column, coloured by how well it goes")
class ChartsTest {

    @Test
    @DisplayName("lowest to highest bar between the bounds; nothing measured is a gap")
    void bars() {
        String plain = Charts.plain(Charts.spark(new double[]{0, 50, 100, Double.NaN}, 0, 100, value -> "<green>"));
        assertThat(plain).isEqualTo("▁▅█ ");
    }

    @Test
    @DisplayName("one colour tag per run, not per bar")
    void colours() {
        String drawn = Charts.spark(new double[]{20, 20, 10}, 0, 20, value -> value >= 19 ? "<green>" : "<red>");
        assertThat(drawn).isEqualTo("<green>██<red>▅");
    }

    @Test
    @DisplayName("health colours: ticks per second high is good, load high is bad")
    void health() {
        assertThat(Charts.tpsColour(19.8)).isEqualTo("<green>");
        assertThat(Charts.tpsColour(17)).isEqualTo("<yellow>");
        assertThat(Charts.tpsColour(9)).isEqualTo("<red>");
        assertThat(Charts.loadColour(40)).isEqualTo("<green>");
        assertThat(Charts.loadColour(80)).isEqualTo("<yellow>");
        assertThat(Charts.loadColour(95)).isEqualTo("<red>");
    }

    @Test
    @DisplayName("a meter of ten, filled to the share")
    void meter() {
        assertThat(Charts.plain(Charts.meter(0.5, "<green>"))).isEqualTo("■■■■■□□□□□");
        assertThat(Charts.plain(Charts.meter(2, "<green>"))).isEqualTo("■■■■■■■■■■");
    }
}
