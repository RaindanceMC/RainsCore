package de.raindancer.core.platform.client;

import de.raindancer.client.protocol.FeatureRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NegotiationTest {

    private static final List<FeatureRange> SERVER = List.of(new FeatureRange(0, 1, 1), new FeatureRange(1, 2, 4));

    @Test
    void theNewestVersionBothSpeakIsAgreed() {
        Negotiation.Outcome outcome = Negotiation.between(SERVER,
                List.of(new FeatureRange(0, 1, 1), new FeatureRange(1, 1, 3)));
        assertThat(outcome.agreed()).containsEntry(0, 1).containsEntry(1, 3).hasSize(2);
        assertThat(outcome.outdated()).isEmpty();
    }

    @Test
    void aNewerModSpeaksTheServersNewest() {
        assertThat(Negotiation.between(SERVER, List.of(new FeatureRange(1, 3, 9))).agreed()).containsEntry(1, 4);
    }

    @Test
    void aModTooOldForAFeatureIsOutdatedThereAndOnlyThere() {
        Negotiation.Outcome outcome = Negotiation.between(SERVER,
                List.of(new FeatureRange(0, 1, 1), new FeatureRange(1, 1, 1)));
        assertThat(outcome.agreed()).containsOnlyKeys(0);
        assertThat(outcome.outdated()).containsExactly(1);
    }

    @Test
    void aModTooNewForThisServerIsJustWithoutTheFeature() {
        Negotiation.Outcome outcome = Negotiation.between(SERVER, List.of(new FeatureRange(1, 5, 6)));
        assertThat(outcome.agreed()).isEmpty();
        assertThat(outcome.outdated()).isEmpty();
    }

    @Test
    void whatTheServerDoesNotKnowAndWhatMakesNoSenseIsLeftOut() {
        Negotiation.Outcome outcome = Negotiation.between(SERVER, List.of(
                new FeatureRange(77, 1, 1), new FeatureRange(1, 4, 2), new FeatureRange(0, 0, 1), new FeatureRange(0, -3, -1)));
        assertThat(outcome.agreed()).isEmpty();
        assertThat(outcome.outdated()).isEmpty();
    }

    @Test
    void aFeatureOfferedTwiceCountsTheFirstTime() {
        assertThat(Negotiation.between(SERVER, List.of(new FeatureRange(1, 2, 2), new FeatureRange(1, 2, 4))).agreed())
                .containsEntry(1, 2);
    }
}
