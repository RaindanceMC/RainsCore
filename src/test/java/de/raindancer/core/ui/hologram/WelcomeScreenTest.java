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
        assertThat(text).contains("Welcome, Ada").contains("/help").doesNotContain("back");
    }

    @Test
    @DisplayName("somebody returning is welcomed back, with no playtime in it")
    void returning() {
        String text = WelcomeScreen.text(facts("Bo", "Raindance", 1, false));
        assertThat(text).contains("Welcome back, Bo");
        assertThat(text.toLowerCase()).doesNotContain("played").doesNotContain("hour").doesNotContain("minute");
    }

    @Test
    @DisplayName("says who else is around, and when nobody is")
    void others() {
        assertThat(WelcomeScreen.text(facts("Bo", "S", 0, false))).contains("nobody else online");
        assertThat(WelcomeScreen.text(facts("Bo", "S", 1, false))).contains("<white>1</white> <gray>other online");
        assertThat(WelcomeScreen.text(facts("Bo", "S", 4, false))).contains("<white>4</white> <gray>others online");
    }

    @Test
    @DisplayName("names are escaped, and a missing server name still reads")
    void escaped() {
        assertThat(WelcomeScreen.text(facts("<red>x", null, 0, true))).contains("\\<red>x");
    }

    @Test
    @DisplayName("the clock is shown, with a moon in the evening")
    void timeOfDay() {
        assertThat(WelcomeScreen.text(facts("Bo", "S", 0, false))).contains("19:42").contains("☾");
    }

    @Test
    @DisplayName("the newest changelog headline and a tip are shown when there are any, escaped")
    void newsAndTip() {
        String text = WelcomeScreen.text(new WelcomeScreen.Facts("Bo", "S", 0, false, EVENING, "Shops <b>big",
                new WelcomeScreen.Tip("spawn", "takes you back to spawn")));
        // one extra line only: the news wins over the tip
        assertThat(text).contains("Shops \\<b>big").contains("/changelog").doesNotContain("/spawn");
        String tipOnly = WelcomeScreen.text(new WelcomeScreen.Facts("Bo", "S", 0, false, EVENING, null,
                new WelcomeScreen.Tip("spawn", "takes you back to spawn")));
        assertThat(tipOnly).contains("/spawn").contains("takes you back to spawn");
        assertThat(WelcomeScreen.text(facts("Bo", "S", 0, false))).doesNotContain("/changelog").doesNotContain("✧");
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

    @Test
    @DisplayName("dressed in the server's own colours: the header and the rules in its brand gradient, a sun or a moon by the time")
    void beautiful() {
        String text = WelcomeScreen.text(facts("Bo", "Hyades", 0, false));
        String gradient = "<gradient:" + de.raindancer.core.ui.chat.Style.brandFrom() + ":" + de.raindancer.core.ui.chat.Style.brandTo();
        assertThat(text).contains(gradient).contains("✦").contains("☾").contains("━");
        assertThat(text).doesNotContain("──────");
        ZonedDateTime morning = EVENING.withHour(9);
        assertThat(WelcomeScreen.text(new WelcomeScreen.Facts("Bo", "S", 0, false, morning, null, null))).contains("☀");
    }
}
