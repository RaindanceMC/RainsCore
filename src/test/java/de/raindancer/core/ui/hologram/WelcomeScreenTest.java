package de.raindancer.core.ui.hologram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("The welcome screen greets by name and never says how long somebody has played")
class WelcomeScreenTest {

    @Test
    @DisplayName("a newcomer is welcomed to the server and pointed at /help")
    void newcomer() {
        String text = CoreScreens.welcomeText("Ada", "Raindance", 3, true);
        assertThat(text).contains("Welcome, Ada!").contains("Raindance").contains("/help").doesNotContain("back");
    }

    @Test
    @DisplayName("somebody returning is welcomed back, with no playtime in it")
    void returning() {
        String text = CoreScreens.welcomeText("Bo", "Raindance", 1, false);
        assertThat(text).contains("Welcome back, Bo!").contains("Raindance");
        assertThat(text.toLowerCase()).doesNotContain("played").doesNotContain("hour").doesNotContain("minute");
    }

    @Test
    @DisplayName("says who else is around, and when nobody is")
    void others() {
        assertThat(CoreScreens.welcomeText("Bo", "S", 0, false)).contains("only one on");
        assertThat(CoreScreens.welcomeText("Bo", "S", 1, false)).contains("<white>1</white> other player on");
        assertThat(CoreScreens.welcomeText("Bo", "S", 4, false)).contains("<white>4</white> other players on");
    }

    @Test
    @DisplayName("names are escaped, and a missing server name still reads")
    void escaped() {
        assertThat(CoreScreens.welcomeText("<red>x", null, 0, true)).contains("\\<red>x").contains("the server");
    }
}
