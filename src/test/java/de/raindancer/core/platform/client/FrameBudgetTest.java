package de.raindancer.core.platform.client;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static de.raindancer.core.platform.client.FrameBudget.Verdict.DROP;
import static de.raindancer.core.platform.client.FrameBudget.Verdict.DROP_AND_REPORT;
import static de.raindancer.core.platform.client.FrameBudget.Verdict.PASS;
import static org.assertj.core.api.Assertions.assertThat;

class FrameBudgetTest {

    private static final UUID RAIN = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private final FrameBudget budget = new FrameBudget(3, 100, 3);

    @Test
    void aSecondHoldsSoManyFrames() {
        assertThat(budget.take(RAIN, 1, 0)).isEqualTo(PASS);
        assertThat(budget.take(RAIN, 1, 10)).isEqualTo(PASS);
        assertThat(budget.take(RAIN, 1, 20)).isEqualTo(PASS);
        assertThat(budget.take(RAIN, 1, 30)).isEqualTo(DROP);
        assertThat(budget.take(RAIN, 1, 1000)).isEqualTo(PASS);
    }

    @Test
    void aSecondHoldsSoManyBytes() {
        assertThat(budget.take(RAIN, 60, 0)).isEqualTo(PASS);
        assertThat(budget.take(RAIN, 41, 1)).isEqualTo(DROP);
        assertThat(budget.take(RAIN, 40, 2)).isEqualTo(PASS);
    }

    @Test
    void everybodyHasTheirOwn() {
        for (int frame = 0; frame < 5; frame++) {
            budget.take(RAIN, 1, frame);
        }
        assertThat(budget.take(OTHER, 1, 6)).isEqualTo(PASS);
    }

    @Test
    void threeSecondsOverInARowIsReportedOnce() {
        assertThat(flood(0)).doesNotContain(DROP_AND_REPORT);
        assertThat(flood(1000)).doesNotContain(DROP_AND_REPORT);
        assertThat(flood(2000)).containsOnlyOnce(DROP_AND_REPORT);
        assertThat(flood(3000)).doesNotContain(DROP_AND_REPORT);
    }

    @Test
    void aQuietSecondStartsTheCountAgain() {
        flood(0);
        flood(1000);
        budget.take(RAIN, 1, 2000);
        assertThat(flood(3000)).doesNotContain(DROP_AND_REPORT);
        assertThat(flood(4000)).doesNotContain(DROP_AND_REPORT);
        assertThat(flood(5000)).contains(DROP_AND_REPORT);
    }

    @Test
    void aPlayerWhoLeftStartsFresh() {
        flood(0);
        budget.forget(RAIN);
        assertThat(budget.take(RAIN, 1, 5)).isEqualTo(PASS);
    }

    private java.util.List<FrameBudget.Verdict> flood(long from) {
        java.util.List<FrameBudget.Verdict> verdicts = new java.util.ArrayList<>();
        for (int frame = 0; frame < 6; frame++) {
            verdicts.add(budget.take(RAIN, 1, from + frame));
        }
        return verdicts;
    }
}
