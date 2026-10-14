package de.raindancer.core.moderation.reports;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AutoReportsTest {

    private static final UUID CHEATER = UUID.randomUUID();

    @AfterEach
    void clear() {
        AutoReports.clear();
    }

    @Test
    @DisplayName("without a plugin taking reports, a filing says nobody took it")
    void nobodyListens() {
        assertThat(AutoReports.available()).isFalse();
        assertThat(AutoReports.file(CHEATER, "Cheater", "movement", "fly, 40 flags")).isEqualTo(AutoReports.Result.NO_SINK);
    }

    @Test
    @DisplayName("a filing goes to the sink with its kind, and what the sink says comes back")
    void filed() {
        List<String> seen = new ArrayList<>();
        AutoReports.provide((subject, name, kind, text) -> {
            seen.add(subject + "|" + name + "|" + kind + "|" + text);
            return AutoReports.Result.FILED;
        });

        assertThat(AutoReports.available()).isTrue();
        assertThat(AutoReports.file(CHEATER, "Cheater", "movement", "fly, 40 flags")).isEqualTo(AutoReports.Result.FILED);
        assertThat(seen).containsExactly(CHEATER + "|Cheater|movement|fly, 40 flags");
    }

    @Test
    @DisplayName("a sink that throws costs that one filing, not the caller")
    void brokenSink() {
        AutoReports.provide((subject, name, kind, text) -> {
            throw new IllegalStateException("broken");
        });
        assertThat(AutoReports.file(CHEATER, "Cheater", "movement", "fly")).isEqualTo(AutoReports.Result.NO_SINK);
    }

    @Test
    @DisplayName("nobody and nothing is not filed")
    void nothingToFile() {
        AutoReports.provide((subject, name, kind, text) -> AutoReports.Result.FILED);
        assertThat(AutoReports.file(null, "x", "movement", "fly")).isEqualTo(AutoReports.Result.NO_SINK);
        assertThat(AutoReports.file(CHEATER, "x", " ", "fly")).isEqualTo(AutoReports.Result.NO_SINK);
        assertThat(AutoReports.file(CHEATER, "x", "movement", "")).isEqualTo(AutoReports.Result.NO_SINK);
    }

    @Test
    @DisplayName("withdrawing somebody else's sink leaves the current one in place")
    void withdrawOnlyYourOwn() {
        AutoReports.Sink mine = (subject, name, kind, text) -> AutoReports.Result.FILED;
        AutoReports.provide(mine);
        AutoReports.withdraw((subject, name, kind, text) -> AutoReports.Result.FILED);
        assertThat(AutoReports.available()).isTrue();
        AutoReports.withdraw(mine);
        assertThat(AutoReports.available()).isFalse();
    }

    @Test
    @DisplayName("a plugin's sink is dropped when its class loader goes, another's is not")
    void forgetFromLoader() {
        AutoReports.provide((subject, name, kind, text) -> AutoReports.Result.FILED);
        assertThat(AutoReports.forgetFrom(new java.net.URLClassLoader(new java.net.URL[0]))).isZero();
        assertThat(AutoReports.available()).isTrue();
        assertThat(AutoReports.forgetFrom(AutoReportsTest.class.getClassLoader())).isEqualTo(1);
        assertThat(AutoReports.available()).isFalse();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("links travel with a report to a queue that keeps them, and staff open them through whoever offers them")
    void links() {
        java.util.List<java.util.Map<String, String>> kept = new java.util.ArrayList<>();
        AutoReports.provide(new AutoReports.Sink() {
            @Override
            public AutoReports.Result file(java.util.UUID subject, String subjectName, String kind, String text) {
                return AutoReports.Result.FILED;
            }

            @Override
            public AutoReports.Result file(java.util.UUID subject, String subjectName, String kind, String text,
                                           java.util.Map<String, String> links) {
                kept.add(links);
                return AutoReports.Result.FILED;
            }
        });
        java.util.List<String> opened = new java.util.ArrayList<>();
        AutoReports.opens("evidence", (staff, id) -> opened.add(id));
        try {
            AutoReports.file(java.util.UUID.randomUUID(), "Alex", "movement", "flew", java.util.Map.of("evidence", "42"));
            org.assertj.core.api.Assertions.assertThat(kept).containsExactly(java.util.Map.of("evidence", "42"));
            org.assertj.core.api.Assertions.assertThat(AutoReports.canOpen("evidence")).isTrue();
            org.assertj.core.api.Assertions.assertThat(AutoReports.open(org.mockito.Mockito.mock(org.bukkit.entity.Player.class), "evidence", "42")).isTrue();
            org.assertj.core.api.Assertions.assertThat(AutoReports.open(org.mockito.Mockito.mock(org.bukkit.entity.Player.class), "replay", "1")).isFalse();
            org.assertj.core.api.Assertions.assertThat(opened).containsExactly("42");
        } finally {
            AutoReports.clear();
        }
    }
}
