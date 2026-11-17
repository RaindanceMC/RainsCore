package de.raindancer.core.ui.statue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StatueRuleTest {

    private final StatueRule rule = new StatueRule();

    private static StatueSpot spot(String line) {
        return StatueSpot.parse(line).orElseThrow();
    }

    @Test
    @DisplayName("the winner stands highest in the middle, second and third lower beside")
    void heights() {
        assertThat(rule.height(1)).isGreaterThan(rule.height(2));
        assertThat(rule.height(2)).isGreaterThan(rule.height(3));
        assertThat(rule.offset(1, 0f)[0]).isCloseTo(0, within(1e-9));
        assertThat(rule.offset(1, 0f)[2]).isCloseTo(0, within(1e-9));
    }

    @Test
    @DisplayName("seen from the crowd it reads 2 · 1 · 3: second on the statues' right hand")
    void sides() {
        // Facing south (yaw 0) the right hand points west, -x; the crowd, facing north, sees it on its left.
        assertThat(rule.offset(2, 0f)[0]).isNegative();
        assertThat(rule.offset(3, 0f)[0]).isPositive();
        assertThat(rule.offset(2, 0f)[2]).isCloseTo(0, within(1e-9));
        // Facing west (yaw 90) the right hand points north, -z.
        assertThat(rule.offset(2, 90f)[2]).isNegative();
        assertThat(rule.offset(2, 90f)[0]).isCloseTo(0, within(1e-9));
        assertThat(rule.offset(2, 0f)[1]).isEqualTo(rule.height(2));
    }

    @Test
    @DisplayName("a whole podium is three statues in one group: 1 in the middle on the ground it was placed on, 2 and 3 beside")
    void podium() {
        List<StatueSpot> three = rule.podium("w", 0.5, 64, 0.5, 0f, "richest", "a1b2c3d4");
        assertThat(three).extracting(StatueSpot::place).containsExactly(1, 2, 3);
        assertThat(three).allSatisfy(each -> {
            assertThat(each.group()).isEqualTo("a1b2c3d4");
            assertThat(each.y()).isEqualTo(64);
            assertThat(each.yaw()).isEqualTo(0f);
            assertThat(each.board()).isEqualTo("richest");
        });
        assertThat(three.get(0).x()).isCloseTo(0.5, within(1e-9));
        assertThat(three.get(1).x()).isCloseTo(-0.5, within(1e-9));
        assertThat(three.get(2).x()).isCloseTo(1.5, within(1e-9));
    }

    @Test
    @DisplayName("podiums written before single statues existed are read as three statues where theirs stood, the same each time")
    void legacy() {
        List<String> lines = List.of("casino 10.50 64.00 -3.25 90.0 casino", "w 1.00 2.00 3.00 0.0 richest 2 -",
                "nonsense");
        List<StatueSpot> read = rule.read(lines);
        assertThat(read).hasSize(4);
        List<StatueSpot> podium = read.subList(0, 3);
        assertThat(podium).extracting(StatueSpot::place).containsExactly(1, 2, 3);
        assertThat(podium).extracting(StatueSpot::group).doesNotContain("-").containsOnly(podium.get(0).group());
        // Facing west (yaw 90) second stands north of the winner, -z.
        assertThat(podium.get(0).z()).isCloseTo(-3.25, within(1e-9));
        assertThat(podium.get(1).z()).isCloseTo(-4.25, within(1e-9));
        assertThat(podium.get(1).x()).isCloseTo(10.5, within(1e-9));
        assertThat(podium).allSatisfy(each -> assertThat(each.board()).isEqualTo("casino"));
        assertThat(rule.read(lines)).isEqualTo(read);
        assertThat(rule.read(read.stream().map(StatueSpot::written).toList())).isEqualTo(read);
        // The group Economy gave such a podium, so its statues keep their key.
        assertThat(podium.get(0).group()).isEqualTo(String.format("%08x",
                "casino 10.50 64.00 -3.25 90.0 casino".hashCode()));
    }

    @Test
    @DisplayName("a ranking statue standing alone is labelled; of a podium only its highest place still standing is")
    void labels() {
        StatueSpot alone = spot("w 0 64 0 0 casino 3 -");
        List<StatueSpot> podium = rule.podium("w", 20.5, 64, 0.5, 0f, "richest", "g1");
        List<StatueSpot> all = new ArrayList<>(podium);
        all.add(alone);
        assertThat(rule.labelled(alone, all, false)).isTrue();
        assertThat(rule.labelled(podium.get(0), all, false)).isTrue();
        assertThat(rule.labelled(podium.get(1), all, false)).isFalse();
        all.remove(podium.get(0));
        assertThat(rule.labelled(podium.get(1), all, false)).isTrue();
        assertThat(rule.labelled(podium.get(2), all, false)).isFalse();
        assertThat(rule.labelled(alone, all, true)).as("a thank-you carries no label").isFalse();
    }

    @Test
    @DisplayName("the label floats clear above the statue's own name tag, not on top of it")
    void labelClearsTheName() {
        double nameTop = 1.8 + 0.5 + 0.3;
        for (int place = 1; place <= StatueSpot.PODIUM; place++) {
            assertThat(rule.labelHeight(place) - rule.height(place)).as("place " + place).isGreaterThan(nameTop);
        }
    }

    @Test
    @DisplayName("statues of one board put up side by side share one label, over the highest place among them")
    void labelsSideBySide() {
        StatueSpot first = spot("w 4229.5 72 -1493.5 135 richest 1 -");
        StatueSpot third = spot("w 4231.5 72 -1494.5 90 richest 3 -");
        StatueSpot second = spot("w 4227.5 72 -1492.5 180 richest 2 -");
        StatueSpot casino = spot("w 4233.5 72 -1494.5 90 casino 2 -");
        StatueSpot farAway = spot("w 4300.5 72 -1494.5 90 richest 2 -");
        List<StatueSpot> all = new ArrayList<>(List.of(first, third, second, casino, farAway));
        assertThat(rule.labelled(first, all, false)).isTrue();
        assertThat(rule.labelled(second, all, false)).isFalse();
        assertThat(rule.labelled(third, all, false)).isFalse();
        assertThat(rule.labelled(casino, all, false)).as("another board keeps its own label").isTrue();
        assertThat(rule.labelled(farAway, all, false)).as("far from the others it stands alone").isTrue();
        all.remove(first);
        assertThat(rule.labelled(second, all, false)).isTrue();
        assertThat(rule.labelled(third, all, false)).isFalse();
        StatueSpot otherWorld = spot("v 4229.5 72 -1493.5 135 richest 3 -");
        all.add(otherWorld);
        assertThat(rule.labelled(otherWorld, all, false)).isTrue();
    }

    @Test
    @DisplayName("statues face the nearest step of the way the placer looks, a podium the nearest quarter")
    void facing() {
        assertThat(rule.facing(100f, 90f)).isEqualTo(90f);
        assertThat(rule.facing(-100f, 90f)).isEqualTo(270f);
        assertThat(rule.facing(30f, 45f)).isEqualTo(45f);
        assertThat(rule.facing(359f, 45f)).isEqualTo(0f);
    }

    @Test
    @DisplayName("the nearest statue within reach is found, optionally only of one board and place")
    void nearest() {
        StatueSpot near = spot("w 1 64 1 0 casino 1 -");
        StatueSpot far = spot("w 4 64 0 0 richest 2 -");
        StatueSpot elsewhere = spot("nether 0 64 0 0 richest 2 -");
        List<StatueSpot> all = List.of(near, far, elsewhere);
        assertThat(rule.nearest(all, "w", 0, 64, 0, 25, each -> true)).contains(near);
        assertThat(rule.nearest(all, "w", 0, 64, 0, 25, each -> each.place() == 2)).contains(far);
        assertThat(rule.nearest(all, "w", 0, 64, 0, 4, each -> each.place() == 2)).isEmpty();
        assertThat(rule.nearest(all, "end", 0, 64, 0, Double.MAX_VALUE, each -> true)).isEmpty();
    }

    @Test
    @DisplayName("a ranking stands on its pedestal, a thank-you on the ground like a mannequin statue")
    void standing() {
        StatueSpot prime = spot("w 0 64 0 0 prime 1 -");
        StatueSpot casino = spot("w 9 64 0 0 casino 1 -");
        assertThat(rule.standingHeight(prime, true)).isZero();
        assertThat(rule.standingHeight(casino, false)).isEqualTo(rule.height(1));
    }

    @Nested
    @DisplayName("thank-you boards")
    class ThankYou {

        @Test
        @DisplayName("with no more people than statues each has one for good; with more, they take turns")
        void rotation() {
            List<String> five = List.of("a", "b", "c", "d", "e");
            assertThat(rule.standing(five.subList(0, 2), 1, 3, 0)).contains("a");
            assertThat(rule.standing(five.subList(0, 2), 3, 3, 0)).isEmpty();
            assertThat(rule.standing(five.subList(0, 2), 2, 3, 99)).contains("b");
            assertThat(rule.standing(List.of(), 1, 3, 0)).isEmpty();

            Set<String> shownNow = new HashSet<>();
            for (int place = 1; place <= 3; place++) {
                shownNow.add(rule.standing(five, place, 3, 0).orElseThrow());
            }
            assertThat(shownNow).hasSize(3);
            Set<String> overTime = new HashSet<>();
            for (long turn = 0; turn < 5; turn++) {
                for (int place = 1; place <= 3; place++) {
                    overTime.add(rule.standing(five, place, 3, turn).orElseThrow());
                }
            }
            assertThat(overTime).as("everybody gets a turn").containsExactlyInAnyOrderElementsOf(five);
        }

        @Test
        @DisplayName("each statue put up is one more: the lowest free place of its own board, none past the most")
        void oneAtATime() {
            assertThat(rule.nextPlace(List.of(), "prime")).hasValue(1);
            List<StatueSpot> some = List.of(spot("w 0 64 0 0 prime 1 -"), spot("w 2 64 0 0 prime 3 -"),
                    spot("w 4 64 0 0 creator 1 -"), spot("w 4 64 0 0 casino 2 -"));
            assertThat(rule.nextPlace(some, "prime")).hasValue(2);
            assertThat(rule.nextPlace(some, "creator")).hasValue(2);
            List<StatueSpot> full = new ArrayList<>();
            for (int place = 1; place <= StatueSpot.MOST; place++) {
                full.add(spot("w " + place + " 64 0 0 prime " + place + " -"));
            }
            assertThat(rule.nextPlace(full, "prime")).isEmpty();
        }
    }
}
