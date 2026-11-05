package de.raindancer.core.moderation.maintenance;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaintenanceTextTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("an update says when to come back, counted from now")
    void update() {
        assertThat(plain(MaintenanceText.closed("update", 1_000_000 + 3 * 60_000, 1_000_000)))
                .isEqualTo("We're updating\n\nWe'll be back in about 3 minutes. Join again then!");
        assertThat(plain(MaintenanceText.closed("Update", 1_000_000 + 150_000, 1_000_000)))
                .contains("back in about 3 minutes");
        assertThat(plain(MaintenanceText.closed("update", 1_000_000 + 40_000, 1_000_000)))
                .contains("back in about a minute");
    }

    @Test
    @DisplayName("an update running late says any moment now rather than a time in the past")
    void late() {
        assertThat(plain(MaintenanceText.closed("update", 1_000_000, 1_000_000 + 30_000)))
                .isEqualTo("We're updating\n\nWe'll be back any moment now. Join again then!");
    }

    @Test
    @DisplayName("any other reason is shown as it was typed")
    void otherReason() {
        String text = plain(MaintenanceText.closed("New spawn", 0, 1_000_000));
        assertThat(text).isEqualTo("Under maintenance\n\nNew spawn\n\nCome back a little later.");
    }

    @Test
    @DisplayName("the screen comes in the server's join/kick frame")
    void framed() {
        try {
            de.raindancer.core.ui.screen.LeaveScreen.configure(() -> "Lilly's SMP", () -> "");
            assertThat(plain(MaintenanceText.closed("update", 0, 1_000_000))).startsWith("✦ LILLY'S SMP ✦");
        } finally {
            de.raindancer.core.ui.screen.LeaveScreen.configure(() -> "", () -> "");
        }
    }

    @Test
    @DisplayName("an update warns a minute ahead; anything else twenty seconds")
    void grace() {
        assertThat(MaintenanceText.graceMillis("update")).isEqualTo(60_000);
        assertThat(MaintenanceText.graceMillis("Update")).isEqualTo(60_000);
        assertThat(MaintenanceText.graceMillis("New spawn")).isEqualTo(20_000);
        assertThat(MaintenanceText.graceMillis("")).isEqualTo(20_000);
    }

    @Test
    @DisplayName("an update's countdown reads exactly like the deploy scripts' announcements, at 60, 30 and 10 s")
    void updateCountdownLines() {
        assertThat(line("update", 60, true)).isEqualTo("[Server] Restart in 60 seconds for an update!");
        assertThat(line("update", 30, false)).isEqualTo("[Server] Restart in 30 seconds.");
        assertThat(line("update", 10, false)).isEqualTo("[Server] Restart in 10 seconds - see you in a minute!");
        assertThat(MaintenanceText.countdown("update", 45, false)).isNull();
        assertThat(MaintenanceText.countdown("update", 5, false)).isNull();
    }

    @Test
    @DisplayName("the colours are the scripts': gold bold tag, yellow, red for the last")
    void updateCountdownColours() {
        Component sixty = MaintenanceText.countdown("update", 60, true);
        Component ten = MaintenanceText.countdown("update", 10, false);

        assertThat(sixty.children().get(0).color()).isEqualTo(net.kyori.adventure.text.format.NamedTextColor.GOLD);
        assertThat(sixty.children().get(0).hasDecoration(net.kyori.adventure.text.format.TextDecoration.BOLD)).isTrue();
        assertThat(sixty.children().get(1).color()).isEqualTo(net.kyori.adventure.text.format.NamedTextColor.YELLOW);
        assertThat(ten.children().get(1).color()).isEqualTo(net.kyori.adventure.text.format.NamedTextColor.RED);
    }

    @Test
    @DisplayName("any other maintenance is said the same way, from wherever its countdown starts")
    void otherCountdownLines() {
        assertThat(line("New spawn", 20, true)).isEqualTo("[Server] Maintenance in 20 seconds: New spawn");
        assertThat(line("", 20, true)).isEqualTo("[Server] Maintenance in 20 seconds.");
        assertThat(line("New spawn", 10, false)).isEqualTo("[Server] Maintenance in 10 seconds - see you soon!");
        assertThat(MaintenanceText.countdown("New spawn", 15, false)).isNull();
    }

    private static String line(String reason, long left, boolean first) {
        return plain(MaintenanceText.countdown(reason, left, first));
    }

    @Test
    @DisplayName("a lagging timer that jumps past a mark still says it")
    void crossedMarks() {
        assertThat(MaintenanceText.crossed(-1, 60)).isEqualTo(60);
        assertThat(MaintenanceText.crossed(-1, 20)).isEqualTo(20);
        assertThat(MaintenanceText.crossed(31, 30)).isEqualTo(30);
        assertThat(MaintenanceText.crossed(11, 9)).isEqualTo(10);
        assertThat(MaintenanceText.crossed(45, 44)).isEqualTo(-1);
        assertThat(MaintenanceText.crossed(10, 9)).isEqualTo(-1);
    }

    @Test
    @DisplayName("a measured update is announced in whole minutes, rounded up, between 1 and 240")
    void updateMinutes() {
        assertThat(MaintenanceText.updateMinutes(150_000)).isEqualTo(3);
        assertThat(MaintenanceText.updateMinutes(120_000)).isEqualTo(2);
        assertThat(MaintenanceText.updateMinutes(5_000)).isEqualTo(1);
        assertThat(MaintenanceText.updateMinutes(0)).isEqualTo(1);
        assertThat(MaintenanceText.updateMinutes(10 * 60 * 60_000L)).isEqualTo(240);
    }
}
