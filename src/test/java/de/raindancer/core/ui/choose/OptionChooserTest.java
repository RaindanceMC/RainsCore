package de.raindancer.core.ui.choose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one part of {@link OptionChooser} that is not a window: what a stored value looks like on a
 * button. The page itself needs a server, the same reason {@code AmountChooser}'s own test is about
 * its arithmetic and nothing else.
 */
class OptionChooserTest {

    @Test
    @DisplayName("an enum constant reads as a sentence, not as shouting")
    void tidiesConstants() {
        assertThat(OptionChooser.readable("LAST_PORTAL")).isEqualTo("Last portal");
        assertThat(OptionChooser.readable("name_world")).isEqualTo("Name world");
        assertThat(OptionChooser.readable("OFF")).isEqualTo("Off");
    }

    @Test
    @DisplayName("a value with nothing in it still has something on the button")
    void handlesNothing() {
        assertThat(OptionChooser.readable(null)).isEqualTo("-");
        assertThat(OptionChooser.readable("  ")).isEqualTo("-");
    }

    @Test
    @DisplayName("the label is only for reading — the stored value is never rewritten")
    void doesNotChangeTheValue() {
        // The chooser hands back the option it was given, exactly as given: readable() is used for
        // the button's name and nowhere else. A value tidied on its way into config.yml would be a
        // value no parser recognises.
        assertThat(OptionChooser.readable("LAST_PORTAL")).isNotEqualTo("LAST_PORTAL");
    }
}
