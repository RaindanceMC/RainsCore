package de.raindancer.core.ui.choose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Picking a number without typing it.
 *
 * <h2>Why Core wants one</h2>
 * Six places already need it — an entry fee, its XP levels, the pantry threshold, an effect level, a claim's
 * depth, a fence height — and every one of them had grown its own answer: nudge buttons at ±1 and ±10, which
 * means forty clicks to set a fee of four hundred, or a chat prompt, which closes the menu and loses whatever
 * was half-configured.
 *
 * <p>The old plugin had this right and it was the one screen nobody complained about. Ported rather than
 * reinvented, with the arithmetic pulled out of the screen so it can be tested without a server — which is the
 * half that was never tested before, and the half where every bug was.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li><b>Clamped, never wrapped.</b> A stepper that turns 400 into the minimum because somebody clicked +100
 *       twice is a stepper nobody trusts.</li>
 *   <li><b>A step that cannot move is shown as unreachable</b> rather than hidden, so the row does not reshuffle
 *       under the cursor as the number changes.</li>
 *   <li><b>Nothing is applied until Accept.</b> Back is the way out, and a dialog that changes nothing until it
 *       is accepted needs no second word for "never mind".</li>
 * </ul>
 */
class AmountChoosingTest {

    @Test
    @DisplayName("a step moves the value by its own size")
    void steppingWorks() {
        assertThat(AmountChooser.stepped(10, 5, 0, 100)).isEqualTo(15);
        assertThat(AmountChooser.stepped(10, -5, 0, 100)).isEqualTo(5);
    }

    @Test
    @DisplayName("a step past an end stops at the end")
    void steppingIsClampedNotWrapped() {
        assertThat(AmountChooser.stepped(95, 100, 0, 100))
                .as("wrapping would turn a fat-fingered +100 into the minimum, which is the one outcome "
                        + "nobody expects")
                .isEqualTo(100);
        assertThat(AmountChooser.stepped(5, -100, 0, 100)).isZero();
    }

    @Test
    @DisplayName("a value already outside the range is brought inside")
    void anOutOfRangeStartIsFixed() {
        // Happens when a server lowers its maximum after somebody set a higher fee. The screen has to open on
        // something legal rather than refuse or show a number it will not accept.
        assertThat(AmountChooser.stepped(500, 0, 0, 100)).isEqualTo(100);
        assertThat(AmountChooser.stepped(-7, 0, 1, 100)).isEqualTo(1);
    }

    @Test
    @DisplayName("a step that changes nothing is known to be unreachable")
    void deadStepsAreVisible() {
        assertThat(AmountChooser.reachable(100, 10, 0, 100))
                .as("shown greyed rather than hidden — a row that reshuffles as the number changes is a row "
                        + "you cannot click twice in the same place")
                .isFalse();
        assertThat(AmountChooser.reachable(90, 10, 0, 100)).isTrue();
        assertThat(AmountChooser.reachable(0, -1, 0, 100)).isFalse();
    }

    @Test
    @DisplayName("a single-value range is legal and does nothing")
    void adegenerateRangeIsSafe() {
        assertThat(AmountChooser.stepped(5, 1, 5, 5)).isEqualTo(5);
        assertThat(AmountChooser.reachable(5, 1, 5, 5)).isFalse();
    }

    @Test
    @DisplayName("a range given the wrong way round is read the right way round")
    void backwardsBoundsDoNotTrap() {
        // Nobody means min > max. Refusing would be a screen that will not open; swapping is what they meant.
        assertThat(AmountChooser.stepped(50, 0, 100, 0)).isEqualTo(50);
        assertThat(AmountChooser.stepped(50, 500, 100, 0)).isEqualTo(100);
    }

    @Test
    @DisplayName("the steps offered are the ones people actually want")
    void thestepsAreUseful() {
        // Not an aesthetic choice: ±1 and ±10 alone is forty clicks to set a fee of four hundred, which is why
        // every screen that had only those grew a chat prompt beside it.
        assertThat(AmountChooser.steps())
                .containsExactly(-100, -10, -1, 1, 10, 100);
    }

    @Test
    @DisplayName("a cyclic range — a time of day, an angle — wraps round instead of stopping, either way")
    void cyclicRangesWrap() {
        // Opt-in only: wrapping is right for something that has no end, and wrong for everything else.
        assertThat(AmountChooser.stepped(23900, 1000, 0, 23999, true)).isEqualTo(900);
        assertThat(AmountChooser.stepped(100, -1000, 0, 23999, true)).isEqualTo(23100);
        assertThat(AmountChooser.stepped(23999, 1, 0, 23999, true)).isZero();
        assertThat(AmountChooser.stepped(0, -1, 0, 23999, true)).isEqualTo(23999);
        assertThat(AmountChooser.stepped(5, 3, 5, 9, true)).isEqualTo(8);
        assertThat(AmountChooser.stepped(95, 100, 0, 100, false)).as("unchanged without it").isEqualTo(100);
        assertThat(AmountChooser.reachable(23999, 1, 0, 23999, true)).isTrue();
        assertThat(AmountChooser.reachable(5, 1, 5, 5, true)).as("one value has nowhere to go").isFalse();
    }

    @Test
    @DisplayName("a caller's own step sizes become a minus and a plus button each, smallest nearest the value")
    void ownSteps() {
        assertThat(AmountChooser.stepsFor(new int[]{1, 17, 167, 1000}))
                .containsExactly(-1000, -167, -17, -1, 1, 17, 167, 1000);
        assertThat(AmountChooser.stepsFor(new int[]{-10, 1, 1, 0}))
                .as("signs, duplicates and zero tidied away").containsExactly(-10, -1, 1, 10);
        assertThat(AmountChooser.stepsFor(new int[]{1, 2, 3, 4, 5}))
                .as("at most four a side fit the row; the finest are kept").containsExactly(-4, -3, -2, -1, 1, 2, 3, 4);
    }
}
