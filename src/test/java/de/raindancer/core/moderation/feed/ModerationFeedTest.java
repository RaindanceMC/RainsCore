package de.raindancer.core.moderation.feed;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModerationFeedTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());

    private final ModerationFeed feed = new ModerationFeed();

    private static Punishment ban() {
        return new Punishment(null, ALICE, PunishmentKind.BAN, null, "x-ray", Instant.EPOCH, null, null, null, null);
    }

    @Test
    @DisplayName("listeners hear punishments, lifts, alerts and reports, in the order they subscribed")
    void deliversToEveryone() {
        List<String> heard = new ArrayList<>();
        feed.onPunishment(notice -> heard.add("a:" + notice.lifted()));
        feed.onPunishment(notice -> heard.add("b:" + notice.lifted()));
        feed.onAnticheat(alert -> heard.add("ac:" + alert.check()));
        feed.onReport(report -> heard.add("r:" + report.id()));

        feed.punished(ban());
        feed.lifted(ban());
        feed.anticheat(new AnticheatAlert(ALICE, "Alice", "Simulation", 7.5, "off by 0.3", 42, "26.3", "fabric"));
        feed.reported(new ReportNotice("R7", ALICE, "Alice", UUID.randomUUID(), "Bob", "flying"));

        assertThat(heard).containsExactly("a:false", "b:false", "a:true", "b:true", "ac:Simulation", "r:R7");
    }

    @Test
    @DisplayName("a closed subscription hears nothing more")
    void unsubscribes() {
        List<String> heard = new ArrayList<>();
        var subscription = feed.onPunishment(notice -> heard.add("x"));
        feed.punished(ban());
        subscription.close();
        feed.punished(ban());
        assertThat(heard).hasSize(1);
    }

    @Test
    @DisplayName("a listener that throws never reaches the caller or the other listeners")
    void failingListenerIsHarmless() {
        List<String> heard = new ArrayList<>();
        feed.onAnticheat(alert -> {
            throw new IllegalStateException("boom");
        });
        feed.onAnticheat(alert -> heard.add("ok"));
        feed.anticheat(new AnticheatAlert(ALICE, "Alice", "Fly", 1, "", 0, null, null));
        assertThat(heard).containsExactly("ok");
    }

    @Test
    @DisplayName("listeners hear admin mode switches")
    void adminModes() {
        List<AdminModeNotice> heard = new ArrayList<>();
        var subscription = feed.onAdminMode(heard::add);
        feed.adminMode(new AdminModeNotice(ALICE, true, 0));
        subscription.close();
        feed.adminMode(new AdminModeNotice(ALICE, false, 5));
        assertThat(heard).containsExactly(new AdminModeNotice(ALICE, true, 0));
    }
}
