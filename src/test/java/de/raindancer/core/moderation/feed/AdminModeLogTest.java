package de.raindancer.core.moderation.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AdminModeLogTest {

    private static final UUID STAFF = UUID.randomUUID();

    private final ModerationFeed feed = new ModerationFeed();
    private final AtomicLong now = new AtomicLong(1_000);
    private final AdminModeLog log = new AdminModeLog(feed, now::get);
    private final List<AdminModeNotice> heard = new ArrayList<>();

    AdminModeLogTest() {
        feed.onAdminMode(heard::add);
    }

    @Test
    @DisplayName("going in and coming out is told, the way out with how long it lasted")
    void inAndOut() {
        log.switched(STAFF, true);
        now.set(61_000);
        log.switched(STAFF, false);

        assertThat(heard).containsExactly(new AdminModeNotice(STAFF, true, 0), new AdminModeNotice(STAFF, false, 60_000));
    }

    @Test
    @DisplayName("being announced as in again (a join while still in) is not a second entry and keeps the clock")
    void stillIn() {
        log.switched(STAFF, true);
        now.set(5_000);
        log.switched(STAFF, true);
        now.set(11_000);
        log.switched(STAFF, false);

        assertThat(heard).hasSize(2);
        assertThat(heard.get(1).durationMillis()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("a way out nobody saw the way in to is still told, with an unknown (0) duration")
    void leftUnseen() {
        log.switched(STAFF, false);

        assertThat(heard).containsExactly(new AdminModeNotice(STAFF, false, 0));
    }

    @Test
    @DisplayName("a second stay is timed from its own start")
    void twoStays() {
        log.switched(STAFF, true);
        now.set(2_000);
        log.switched(STAFF, false);
        now.set(100_000);
        log.switched(STAFF, true);
        now.set(103_000);
        log.switched(STAFF, false);

        assertThat(heard.get(3).durationMillis()).isEqualTo(3_000);
    }
}
