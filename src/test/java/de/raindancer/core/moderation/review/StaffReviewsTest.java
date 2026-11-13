package de.raindancer.core.moderation.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StaffReviewsTest {

    private final StaffReviews reviews = new StaffReviews();
    private final UUID ada = UUID.randomUUID();

    private StaffReviews.Review waiting() {
        return StaffReviews.Review.waiting("mapart", "k3x9q", ada, "Ada", "Map art 2×2", "https://example.com/a.png",
                new byte[]{1, 2, 3}, "image/jpeg");
    }

    @Test
    @DisplayName("a posted review reaches every listener; a closed one hears nothing more")
    void listeners() {
        List<StaffReviews.Review> heard = new ArrayList<>();
        StaffReviews.Subscription subscription = reviews.onReview(heard::add);
        reviews.post(waiting());
        subscription.close();
        reviews.post(waiting());

        assertThat(heard).hasSize(1);
        assertThat(heard.getFirst().state()).isEqualTo(StaffReviews.State.WAITING);
        assertThat(heard.getFirst().preview()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("a listener that throws costs only itself")
    void brokenListener() {
        List<StaffReviews.Review> heard = new ArrayList<>();
        reviews.onReview(review -> {
            throw new IllegalStateException("boom");
        });
        reviews.onReview(heard::add);
        reviews.post(waiting());
        assertThat(heard).hasSize(1);
    }

    @Test
    @DisplayName("a decision goes to the decider of its kind; no decider here says so")
    void decide() {
        List<String> asked = new ArrayList<>();
        reviews.decider("mapart", (id, approve, by, reason) -> {
            asked.add(id + " " + approve + " " + by + " " + reason);
            return approve ? StaffReviews.Outcome.APPROVED : StaffReviews.Outcome.REFUSED;
        });

        assertThat(reviews.decide("mapart", "k3x9q", true, "Mo", "")).isEqualTo(StaffReviews.Outcome.APPROVED);
        assertThat(reviews.decide("MAPART", "k3x9q", false, "Mo", "blurry")).isEqualTo(StaffReviews.Outcome.REFUSED);
        assertThat(reviews.decide("statue", "x", true, "Mo", "")).isEqualTo(StaffReviews.Outcome.NOT_HERE);
        assertThat(asked).containsExactly("k3x9q true Mo ", "k3x9q false Mo blurry");
    }

    @Test
    @DisplayName("a decider that throws answers FAILED; a closed one is gone")
    void brokenDecider() {
        StaffReviews.Subscription gone = reviews.decider("mapart", (id, approve, by, reason) -> {
            throw new IllegalStateException("boom");
        });
        assertThat(reviews.decide("mapart", "a", true, "Mo", null)).isEqualTo(StaffReviews.Outcome.FAILED);
        gone.close();
        assertThat(reviews.decide("mapart", "a", true, "Mo", null)).isEqualTo(StaffReviews.Outcome.NOT_HERE);
    }

    @Test
    @DisplayName("later states keep who and why, and drop the preview nobody needs again")
    void states() {
        StaffReviews.Review refused = waiting().refused("Mo", "blurry");
        assertThat(refused.state()).isEqualTo(StaffReviews.State.REFUSED);
        assertThat(refused.by()).isEqualTo("Mo");
        assertThat(refused.reason()).isEqualTo("blurry");
        assertThat(refused.preview()).isEmpty();
        assertThat(waiting().approved("Mo").state()).isEqualTo(StaffReviews.State.APPROVED);
        assertThat(waiting().done("Mo", "4 maps drawn").detail()).isEqualTo("4 maps drawn");
        assertThat(waiting().failed("Mo", "404").reason()).isEqualTo("404");
        assertThat(waiting().withDetail("could not load").detail()).isEqualTo("could not load");
    }

    @Test
    @DisplayName("a request from outside the game goes to the intake of its kind; none here says so; a throwing one fails")
    void intake() {
        List<StaffReviews.Submission> got = new ArrayList<>();
        StaffReviews.Subscription open = reviews.intake("mapart", submission -> {
            got.add(submission);
            return new StaffReviews.Answer("asked", "k3x9q", "Asked!");
        });
        StaffReviews.Submission from = new StaffReviews.Submission("mapart", ada, "Ada",
                "https://cdn.example/a.png", "2x2", "discord");

        assertThat(reviews.submit(from)).isEqualTo(new StaffReviews.Answer("asked", "k3x9q", "Asked!"));
        assertThat(got).containsExactly(from);
        assertThat(reviews.submit(new StaffReviews.Submission("statue", ada, "Ada", "", "", "discord")).outcome())
                .isEqualTo("not_here");
        open.close();
        assertThat(reviews.submit(from).outcome()).isEqualTo("not_here");

        reviews.intake("mapart", submission -> {
            throw new IllegalStateException("boom");
        });
        assertThat(reviews.submit(from).outcome()).isEqualTo("failed");
    }
}
