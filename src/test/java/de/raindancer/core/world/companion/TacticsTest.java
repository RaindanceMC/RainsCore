package de.raindancer.core.world.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("how a companion fights")
class TacticsTest {

    private static final Steering.Vec OWNER = new Steering.Vec(0, 64, 0);
    private static final Steering.Vec FOE = new Steering.Vec(10, 64, 0);

    private static Tactics.Situation at(Steering.Vec here, Fighting fighting, Tactic tactic, boolean ready) {
        return new Tactics.Situation(here, FOE, OWNER, 1.5, ready, 1, fighting, tactic);
    }

    private static double flat(Steering.Vec a, Steering.Vec b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    @Test
    @DisplayName("ready to bite, a fighter goes right in")
    void biting() {
        Tactics.Plan plan = Tactics.plan(at(new Steering.Vec(7, 64, 0), Fighting.MELEE, Tactic.BALANCED, true));
        assertThat(flat(plan.aim(), FOE)).isLessThan(0.01);
        assertThat(plan.reach()).isLessThan(1.5);
    }

    @Test
    @DisplayName("between bites it circles its foe instead of sitting inside it")
    void circling() {
        Steering.Vec here = new Steering.Vec(8.5, 64, 0);
        Tactics.Plan plan = Tactics.plan(at(here, Fighting.MELEE, Tactic.BALANCED, false));
        assertThat(flat(plan.aim(), FOE)).isBetween(1.5, 3.5);
        assertThat(Math.abs(plan.aim().z())).isGreaterThan(0.5);
        Tactics.Plan other = Tactics.plan(new Tactics.Situation(here, FOE, OWNER, 1.5, false, -1, Fighting.MELEE,
                Tactic.BALANCED));
        assertThat(Math.signum(other.aim().z())).isEqualTo(-Math.signum(plan.aim().z()));
    }

    @Test
    @DisplayName("hit-and-run backs out of reach until it is ready again")
    void hitAndRun() {
        Steering.Vec here = new Steering.Vec(8.8, 64, 0);
        Tactics.Plan plan = Tactics.plan(at(here, Fighting.MELEE, Tactic.HIT_AND_RUN, false));
        assertThat(flat(plan.aim(), FOE)).isGreaterThan(3);
        assertThat(plan.aim().x()).isLessThan(here.x());
    }

    @Test
    @DisplayName("relentless stays on its foe between bites")
    void relentless() {
        Tactics.Plan plan = Tactics.plan(at(new Steering.Vec(8.8, 64, 0), Fighting.MELEE, Tactic.RELENTLESS, false));
        assertThat(flat(plan.aim(), FOE)).isLessThan(0.01);
    }

    @Test
    @DisplayName("a ranged one keeps its distance, comes closer when too far and backs off when its foe comes close")
    void ranged() {
        Tactics.Plan near = Tactics.plan(at(new Steering.Vec(8, 64, 0), Fighting.RANGED, Tactic.BALANCED, true));
        assertThat(flat(near.aim(), FOE)).isGreaterThan(4);
        assertThat(near.aim().x()).isLessThan(8);

        Tactics.Plan far = Tactics.plan(at(new Steering.Vec(-15, 64, 0), Fighting.RANGED, Tactic.BALANCED, true));
        assertThat(far.aim().x()).isGreaterThan(-15);

        Steering.Vec fine = new Steering.Vec(3, 64, 0);
        Tactics.Plan keep = Tactics.plan(at(fine, Fighting.RANGED, Tactic.BALANCED, true));
        assertThat(flat(keep.aim(), FOE)).isBetween(Tactics.band(Tactic.BALANCED)[0] - 0.5,
                Tactics.band(Tactic.BALANCED)[1] + 0.5);
    }

    @Test
    @DisplayName("a guard stands between its owner and the threat, and only bites what comes close")
    void guard() {
        Tactics.Plan hold = Tactics.plan(at(new Steering.Vec(0, 64, 3), Fighting.GUARD, Tactic.BALANCED, true));
        assertThat(hold.aim().x()).isBetween(1.0, 4.0);
        assertThat(hold.aim().z()).isEqualTo(0, within(0.01));

        Steering.Vec close = new Steering.Vec(3, 64, 0);
        Tactics.Plan bite = Tactics.plan(new Tactics.Situation(new Steering.Vec(2, 64, 0), close, OWNER, 1.5, true, 1,
                Fighting.GUARD, Tactic.BALANCED));
        assertThat(flat(bite.aim(), close)).isLessThan(0.01);
    }

    @Test
    @DisplayName("a healer stays by its owner")
    void healer() {
        Tactics.Plan plan = Tactics.plan(at(new Steering.Vec(6, 64, 0), Fighting.HEALER, Tactic.BALANCED, true));
        assertThat(flat(plan.aim(), OWNER)).isLessThan(0.01);
    }

    @ParameterizedTest
    @EnumSource(Tactic.class)
    @DisplayName("bites come at a slightly uneven pace, never faster than a little under its attack time")
    void jitter(Tactic tactic) {
        Random random = new Random(5);
        int lowest = Integer.MAX_VALUE;
        int highest = 0;
        for (int i = 0; i < 400; i++) {
            int wait = Tactics.cooldown(20, random);
            lowest = Math.min(lowest, wait);
            highest = Math.max(highest, wait);
        }
        assertThat(lowest).isGreaterThanOrEqualTo(17);
        assertThat(highest).isLessThanOrEqualTo(24);
        assertThat(highest).isGreaterThan(lowest);
    }

    @Test
    @DisplayName("how far it chases, how long before it gives up and when it turns back differ by tactic")
    void tempers() {
        assertThat(Tactics.leash(Tactic.CAUTIOUS)).isLessThan(Tactics.leash(Tactic.BALANCED));
        assertThat(Tactics.leash(Tactic.RELENTLESS)).isGreaterThan(Tactics.leash(Tactic.BALANCED));
        assertThat(Tactics.patience(Tactic.RELENTLESS)).isGreaterThan(Tactics.patience(Tactic.BALANCED));
        assertThat(Tactics.turnsBack(0.2, Tactic.CAUTIOUS)).isGreaterThanOrEqualTo(0.4);
        assertThat(Tactics.turnsBack(0.2, Tactic.RELENTLESS)).isLessThan(0.2);
        assertThat(Tactics.turnsBack(0.2, Tactic.BALANCED)).isEqualTo(0.2);
    }
}
