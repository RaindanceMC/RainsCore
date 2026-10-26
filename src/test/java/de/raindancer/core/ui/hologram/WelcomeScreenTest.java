package de.raindancer.core.ui.hologram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("The welcome screen greets by name and never says how long somebody has played")
class WelcomeScreenTest {

    private static final ZonedDateTime EVENING = ZonedDateTime.of(2026, 10, 26, 19, 42, 0, 0, ZoneOffset.UTC);

    private static WelcomeScreen.Facts facts(String name, String server, int others, boolean newcomer) {
        return new WelcomeScreen.Facts(name, server, others, newcomer, EVENING, null, null);
    }

    @Test
    @DisplayName("a newcomer is welcomed to the server and pointed at /help")
    void newcomer() {
        String text = WelcomeScreen.text(facts("Ada", "Raindance", 3, true));
        assertThat(text).contains("Welcome, Ada!").contains("Raindance").contains("/help").doesNotContain("back");
    }

    @Test
    @DisplayName("somebody returning is welcomed back, with no playtime in it")
    void returning() {
        String text = WelcomeScreen.text(facts("Bo", "Raindance", 1, false));
        assertThat(text).contains("Welcome back, Bo!").contains("Raindance");
        assertThat(text.toLowerCase()).doesNotContain("played").doesNotContain("hour").doesNotContain("minute");
    }

    @Test
    @DisplayName("says who else is around, and when nobody is")
    void others() {
        assertThat(WelcomeScreen.text(facts("Bo", "S", 0, false))).contains("only one on");
        assertThat(WelcomeScreen.text(facts("Bo", "S", 1, false))).contains("<white>1</white> other player on");
        assertThat(WelcomeScreen.text(facts("Bo", "S", 4, false))).contains("<white>4</white> other players on");
    }

    @Test
    @DisplayName("names are escaped, and a missing server name still reads")
    void escaped() {
        assertThat(WelcomeScreen.text(facts("<red>x", null, 0, true))).contains("\\<red>x").contains("the server");
    }

    @Test
    @DisplayName("the time of day picks the greeting and the clock is shown")
    void timeOfDay() {
        assertThat(WelcomeScreen.text(facts("Bo", "S", 0, false))).contains("Good evening").contains("19:42")
                .contains("Monday");
        assertThat(WelcomeScreen.greeting(7)).isEqualTo("Good morning");
        assertThat(WelcomeScreen.greeting(14)).isEqualTo("Good afternoon");
        assertThat(WelcomeScreen.greeting(2)).isEqualTo("Up late");
    }

    @Test
    @DisplayName("the newest changelog headline and a tip are shown when there are any, escaped")
    void newsAndTip() {
        String text = WelcomeScreen.text(new WelcomeScreen.Facts("Bo", "S", 0, false, EVENING, "Shops <b>big",
                new WelcomeScreen.Tip("spawn", "takes you back to spawn")));
        assertThat(text).contains("New:").contains("Shops \\<b>big").contains("/spawn").contains("takes you back to spawn");
        assertThat(WelcomeScreen.text(facts("Bo", "S", 0, false))).doesNotContain("New:").doesNotContain("Tip");
    }

    @Test
    @DisplayName("tips only name commands the server has, and turn over every 15 seconds")
    void tips() {
        Set<String> installed = Set.of("help", "spawn", "msg");
        List<WelcomeScreen.Tip> usable = WelcomeScreen.usable(WelcomeScreen.TIPS, installed::contains);
        assertThat(usable).isNotEmpty().allMatch(tip -> installed.contains(tip.command()));
        assertThat(WelcomeScreen.tip(usable, 0)).isEqualTo(WelcomeScreen.tip(usable, 14));
        assertThat(WelcomeScreen.tip(usable, 15)).isNotEqualTo(WelcomeScreen.tip(usable, 0));
        assertThat(WelcomeScreen.tip(List.of(), 0)).isNull();
    }
}
